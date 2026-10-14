(ns website.build-modules-test
  (:require [clojure.test :refer [deftest is testing]]
            [website.build.modules :as modules]
            [website.build.site :as site]
            [website.build.packaging :as packaging]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(deftest module-build-and-runtime-catalog-have-one-owner
  (let [found (modules/discover)
        by-id (into {} (map (juxt :id identity)) found)]
    (is (every? #(contains? by-id %) [:blog :cv :docs :test :user :link-preview :carousel :contact :supabase]))
    (is (not (contains? by-id :main)))
    (is (= 'website.modules.experiments.module (get-in by-id [:test :entry])))
    (is (= #{:main :user :link-preview} (get-in by-id [:blog :depends-on])))
    (doseq [id [:cv :home]]
      (is (= #{:main :carousel} (get-in by-id [id :depends-on]))))
    (is (= #{:main :maps :carousel} (get-in by-id [:strava :depends-on])))
    (doseq [id [:carousel :contact]]
      (is (= #{:main} (get-in by-id [id :depends-on]))))
    (is (= #{:main} (get-in by-id [:user :depends-on])))
    (is (= #{:main :markdown} (get-in by-id [:link-preview :depends-on])))
    (is (= (set (keys by-id)) (set (keys (modules/bundles)))))))

(deftest development-console-is-selected-only-in-enabled-development-builds
  (let [all (modules/all-declarations)
        selected (fn [settings development?]
                   (set (map :id (modules/select-declarations all settings development?))))
        console (first (filter #(= :dev-console (:id %)) all))]
    (is (= 'website.modules.dev-console.module (:entry console)))
    (is (true? (:development-only? console)))
    (is (not ((selected {:modules {}} false) :dev-console)))
    (is ((selected {:modules {}} true) :dev-console))
    (is (not ((selected {:modules {:dev-console {:enabled false}}} true) :dev-console)))
    (with-redefs [site/settings (constantly {:modules {}})]
      (is (not (contains? (modules/bundles false) :dev-console)))
      (is (contains? (modules/bundles true) :dev-console)))
    (is (modules/development-build? {:mode :dev, :compiler-options {:reader-features #{:browser :dev}}}))
    (is (not (modules/development-build? {:mode :release, :compiler-options {:reader-features #{:browser :dev}}})))
    (is (not (modules/development-build? {:mode :dev, :compiler-options {:reader-features #{:ssr :node}}})))
    (let [state {:mode :release
                 :build-sources [:console]
                 :sources {:console {:resource-name "website/modules/dev_console/module.cljs"}}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Disabled capability"
                           (modules/check-site-sources state))))))

(deftest declaration-is-data-and-does-not-evaluate-browser-code
  (let [file (java.io.File/createTempFile "module-declaration" ".cljs")]
    (try
      (spit file "(ns example.module {:bundle/depends-on #{:main :user}})\n(def spec {:id :example :init #(js/alert :never)})")
      (is (= {:id :example :entry 'example.module :depends-on #{:main :user}}
             (modules/declaration file)))
      (spit file "(ns example.module)\n(def spec {:id (identity :example)})")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal spec" (modules/declaration file)))
      (spit file "#=(throw (Exception. \"must not run\"))")
      (is (thrown? RuntimeException (modules/declaration file)))
      (finally (.delete file)))))

(deftest shared-code-consumers-require-the-formatter-without-eager-browser-dependency
  (let [declarations (modules/all-declarations)
        by-id (into {} (map (juxt :id identity)) declarations)]
    (doseq [owner [:markdown :data-inspector]]
      (is (= #{:highlight} (get-in by-id [owner :server :requires])))
      (is (not (contains? (get-in by-id [owner :depends-on]) :highlight))))
    (doseq [owner [:markdown-editor :blog-editor]]
      (is (contains? (get-in by-id [owner :depends-on]) :highlight)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Disabled or missing module dependency"
                         (modules/select-declarations declarations
                           {:modules {:highlight {:enabled false}}})))))

(deftest stylesheet-catalog-contains-transitive-code-dependencies
  (let [file (java.io.File/createTempFile "module-styles" ".cljs")]
    (try
      (spit file "(ns example.module)\n(def spec {:id :example :styles [\"/css/example.css\"]})")
      (is (= ["/css/example.css"] (:styles (modules/declaration file))))
      (spit file "(ns example.module)\n(def spec {:id :example :styles (identity [])})")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal vector" (modules/declaration file)))
      (finally (.delete file)))))

(deftest runtime-catalogs-track-declarations-only-in-shadow-expansion
  (let [*reads (atom [])
        env {:ns {:name 'website.catalog-fixture}}
        expand-styles (deref #'modules/style-definitions)
        expand-loadables (deref #'modules/loadables)]
    (with-redefs [clojure.core/requiring-resolve
                  (fn [symbol]
                    (is (= 'shadow.resource/slurp-resource symbol))
                    (fn [env path] (swap! *reads conj [env path]) ""))]
      (expand-styles nil nil)
      (expand-loadables nil nil)
      (is (empty? @*reads) "JVM catalog expansion has no compiler dependency")
      (let [styles (expand-styles nil env)
            resources (map second @*reads)]
        (is (= (set (keys styles)) (set (map :id (modules/discover)))))
        (is (some #{site/resource} resources))
        (is (= (count resources) (count (distinct resources)))
            "Declaration and icon owners are tracked once")
        (doseq [{:keys [entry]} (modules/discover)]
          (let [path (str (-> (str entry)
                             (string/replace "." "/")
                             (string/replace "-" "_")) ".cljs")]
            (is (some #{path} resources) "Every discovered declaration is tracked")))
        (is (some #{"website/modules/main/module.cljs"} resources))
        (is (some #{"website/components/shell.cljs"} resources))
        (is (some #{"website/modules/user/views.cljc"} resources))
        (is (some #{"/css/website/modules/icons-user.min.css"} (:paths (:user styles))))
        (is (every? io/resource resources) "Every tracked entry resolves on the source classpath")
        (is (every? #(= env (first %)) @*reads)))
      (reset! *reads [])
      (expand-loadables nil env)
      (is (= (+ 1 (count (modules/discover)) (if (io/resource site/overrides-resource) 1 0)) (count @*reads))))))

(deftest deferred-ssr-styles-remain-in-the-runtime-catalog
  (let [file (java.io.File/createTempFile "module-style-policy" ".cljs")]
    (try
      (spit file "(ns example.module)\n(def spec {:id :example :styles [\"/css/example.css\"] :ssr-styles :deferred})")
      (is (= :deferred (:ssr-styles (modules/declaration file))))
      (is (= ["/css/example.css"] (:styles (modules/declaration file))))
      (spit file "(ns example.module)\n(def spec {:id :example :ssr-styles (identity :deferred)})")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal" (modules/declaration file)))
      (finally (.delete file)))))

(deftest site-overrides-preserve-unspecified-file-options
  (is (= {:modules {:blog {:enabled true :comments false} :chat {:enabled false}}
          :pages {:home true :cv false}}
         (site/merge-config {:modules {:blog {:enabled true :comments true} :chat {:enabled false}}
                             :pages {:home true :cv true}}
                            {:modules {:blog {:comments false}} :pages {:cv false}})))
  (doseq [config [{:modules {:main {:enabled false}}}
                  {:modules {:coercion {:enabled false}}}
                  {:modules {:blog {:comments "false"}}}
                  {:modules {} :secrets {}}]]
    (is (thrown? clojure.lang.ExceptionInfo (site/validate! config)))))

(deftest invalid-site-selections-fail-before-building
  (doseq [config [{:modules {:supabase {:enabled false}}}
                  {:modules {:missing {:enabled true}}}
                  {:modules {:blog {:comment false}}}
                  {:modules {:user {:enabled false}}}
                  {:modules {:blog {:enabled false}}}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (modules/select-declarations (modules/all-declarations) config)))))

(deftest local-capture-requires-its-renderer-without-a-browser-bundle-dependency
  (let [declarations (modules/all-declarations)
        capture (first (filter #(= :page-capture (:id %)) declarations))]
    (is (= #{:main} (:depends-on capture)))
    (is (= #{:page-render} (get-in capture [:server :requires])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"module dependency"
                         (modules/select-declarations
                           declarations {:modules {:page-render {:enabled false}}})))
    (is (seq (modules/select-declarations
               declarations {:modules {:page-render {:enabled false}
                                       :page-capture {:enabled false}}})))))

(deftest authoring-children-follow-parent-capabilities-before-code-and-route-discovery
  (let [all (modules/all-declarations)
        by-id (into {} (map (juxt :id identity)) all)
        settings {:modules {:blog {:enabled false}}}]
    (is (= :blog (:site/owner (get by-id :blog-editor))))
    (is (= :blog-editor (:site/owner (get by-id :markdown-editor))))
    (is (not (packaging/owner-enabled? settings by-id :blog-editor)))
    (is (not (packaging/owner-enabled? settings by-id :markdown-editor)))
    (is (packaging/owner-enabled? settings by-id :markdown-components))
    (is (not (packaging/owner-enabled? {:modules {:blog-editor {:enabled false}}} by-id :markdown-editor)))
    (is (packaging/owner-enabled? {:modules {}} by-id :markdown-editor))))
(deftest source-graph-checks-use-one-fresh-settings-snapshot-per-build
  (let [*reads (atom 0)
        *settings (atom {:modules {}})
        ids (range 1000)
        state {:build-sources ids
               :sources (into {} (map (fn [id] [id {:resource-name "cljs/core.cljs"}]) ids))}]
    (with-redefs [site/settings #(do (swap! *reads inc) @*settings)]
      (is (identical? state (modules/check-site-sources state)))
      (is (= 1 @*reads) "Source count must not multiply settings reads")
      (reset! *settings {:modules {:cv {:enabled false}}})
      (let [disabled {:build-sources [:cv]
                      :sources {:cv {:ns 'website.modules.cv.views
                                     :resource-name "website/modules/cv/views.cljs"}}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Disabled capability"
                             (modules/check-site-sources disabled)))
        (is (= 2 @*reads) "The next build reads changed settings"))
      (let [comments {:build-sources [:comments]
                      :sources {:comments {:ns 'website.modules.blog.comments
                                           :resource-name "website/modules/blog/comments.cljs"}}}]
        (doseq [selection [{:modules {:blog {:comments false}}}
                          {:modules {:blog {:enabled false}}}]]
          (reset! *settings selection)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Disabled capability"
                               (modules/check-site-sources comments))))
        (reset! *settings {:modules {:blog {:enabled true :comments true}}})
        (is (identical? comments (modules/check-site-sources comments)))))))
