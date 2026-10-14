(ns website.build-modules-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [website.schema.dsl :as dsl]
            [website.schema.declarations :as declarations]
            [website.build.modules :as modules]
            [website.build.site :as site]
            [website.loader.code :as code]
            [website.build.packaging :as packaging]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(deftest module-build-and-runtime-catalog-have-one-owner
  (let [found (modules/discover)
        by-id (into {} (map (juxt :id identity)) found)]
    (is (every? #(contains? by-id %) [:blog :cv :docs :test :user :link-preview :carousel :contact :supabase]))
    (is (not (contains? by-id :main)))
    (is (= 'website.modules.experiments.module (get-in by-id [:test :entry])))
    (is (= #{:main :user} (get-in by-id [:blog :depends-on])))
    (doseq [id [:cv :home]]
      (is (= #{:main :carousel} (get-in by-id [id :depends-on]))))
    (is (= #{:main :maps :carousel} (get-in by-id [:strava :depends-on])))
    (doseq [id [:carousel :contact]]
      (is (= #{:main} (get-in by-id [id :depends-on]))))
    (is (= #{:main} (get-in by-id [:user :depends-on])))
    (is (= #{:main} (get-in by-id [:link-preview :depends-on])))
    (is (= (set (keys by-id)) (set (keys (modules/bundles)))))))

(deftest search-previews-require-both-owning-capabilities
  (let [all (modules/all-declarations)
        selected (fn [settings] (set (map :id (modules/select-declarations all settings))))]
    (is ((selected {:modules {}}) :blog-preview))
    (doseq [settings [{:modules {:search {:enabled false}}}
                     {:modules {:blog {:enabled false}}}]]
      (is (not ((selected settings) :blog-preview))))
    (is (not ((selected {:modules {:search {:enabled false}}}) :github-preview)))
    (is (not ((selected {:modules {:search {:enabled false}}}) :home-preview)))))

(deftest early-packaging-and-compiler-discovery-share-conventions
  (let [packaged (into {} (map (juxt :id identity)) (#'packaging/declarations))]
    (doseq [declaration (modules/all-declarations modules/root)
            :when (not (:development-only? declaration))]
      (is (= (select-keys declaration [:id :site/owner :styles])
             (select-keys (get packaged (:id declaration)) [:id :site/owner :styles]))))))

(deftest preview-usage-is-optional-rather-than-a-bundle-prerequisite
  (let [declarations (modules/all-declarations)
        by-id (into {} (map (juxt :id identity)) declarations)]
    (doseq [id [:blog :chat :search]]
      (is (not (contains? (get-in by-id [id :depends-on]) :link-preview))))
    (is (seq (modules/select-declarations declarations
               {:modules {:link-preview {:enabled false}}})))))

(deftest comment-views-are-an-optional-source-boundary
  (doseq [path ["src/frontend/website/modules/blog/views.cljc"]]
    (doseq [comments? [false true]]
      (with-open [reader (java.io.PushbackReader. (io/reader path))]
        (let [features (cond-> #{:cljs} comments? (conj :feature/blog-comments))
              namespace (read {:read-cond :allow, :features features} reader)
              symbols (set (filter symbol? (tree-seq coll? seq namespace)))]
          (is (= comments? (contains? symbols 'website.modules.blog.comment-views)))))))
  (with-redefs [site/settings (constantly {:modules {:blog {:comments false}}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Disabled capability"
                         (modules/check-site-sources
                           {:build-sources [:comments]
                            :sources {:comments {:resource-name "website/modules/blog/comment_views.cljs"}}})))))

(deftest standalone-user-does-not-require-markdown-or-previews
  (let [all (modules/all-declarations)
        retained #{:main :coercion :user :supabase}
        settings {:modules (into {} (for [{:keys [id]} all :when (not (retained id))]
                                      [id {:enabled false}]))}]
    (is (contains? (set (map :id (modules/select-declarations all settings))) :user))
    (with-open [reader (java.io.PushbackReader. (io/reader "src/frontend/website/modules/user/views.cljc"))]
      (let [namespace (read {:read-cond :allow :features #{:cljs :feature/blog-comments}} reader)
            symbols (filter symbol? (tree-seq coll? seq namespace))]
        (is (not-any? #(re-find #"website.modules.(markdown|link-preview)" (str %)) symbols))))))

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
      (spit file "(ns example.module)\n(def spec {:bundle/depends-on #{:main :user} :init #(js/alert :never)})")
      (is (= {:id :example :entry 'example.module :depends-on #{:main :user}}
             (modules/declaration file)))
      (spit file "(ns example.module)\n(def spec {:id (identity :example)})")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal spec" (modules/declaration file)))
      (spit file "#=(throw (Exception. \"must not run\"))")
      (is (thrown? RuntimeException (modules/declaration file)))
      (finally (.delete file)))))

(deftest module-conventions-preserve-explicit-overrides-and-reject-invalid-declarations
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory "module-defaults" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file directory "module.cljs")
        stylesheet (io/file directory "maps.min.css")]
    (try
      (with-redefs [packaging/stylesheet-root (str directory)
                    packaging/stylesheet-source-root (str directory)]
        (spit file "(ns website.modules.maps.module)\n(def spec {})")
        (is (= {:id :maps, :entry 'website.modules.maps.module, :depends-on #{:main}}
               (modules/declaration file)))
        (let [source (io/file directory "maps.scss")]
          (try
            (spit source "body{}")
            (is (= ["/css/website/modules/maps.min.css"] (:styles (modules/declaration file)))
                "Cold packaging and AOT resolve source-owned styles before CSS generation")
            (finally (.delete source))))
        (spit stylesheet "body{}")
        (is (= ["/css/website/modules/maps.min.css"] (:styles (modules/declaration file))))
        (spit file "(ns website.modules.maps.module)\n(def spec {:id :custom :styles [\"/css/custom.css\"]})")
        (is (= :custom (:id (modules/declaration file))))
        (is (= ["/css/custom.css"] (:styles (modules/declaration file))))
        (spit file "(ns website.modules.maps.module)\n(def spec {:styles []})")
        (is (nil? (:styles (modules/declaration file))))
        (doseq [source ["(ns website.modules.maps.module)"
                        "(ns website.modules.maps.module) (def spec (identity {}))"
                        "(ns website.modules.maps.module) (def spec {:id nil})"]]
          (spit file source)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal spec" (modules/declaration file))))
        (spit file "(ns website.modules.maps.module {:bundle/depends-on #{:main}}) (def spec {})")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not ns metadata" (modules/declaration file))))
      (finally
        (.delete stylesheet)
        (.delete file)
        (.delete directory)))))

(deftest runtime-module-identity-is-resolved-before-validation
  (is (= {:id :maps, :view {}} (code/module-spec :maps {:view {}})))
  (is (= {:id :custom} (code/module-spec :maps {:id :custom}))))

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
                  {:modules {:user {:enabled false}}}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (modules/select-declarations (modules/all-declarations) config)))))

(deftest search-remains-available-without-blog-sources
  (let [selected (modules/select-declarations (modules/all-declarations)
                                             {:modules {:blog {:enabled false}}})
        ids (set (map :id selected))]
    (is (ids :search))
    (is (not (some ids [:blog :blog-editor :markdown-editor])))))

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
    (is (= :blog (:site/owner (get by-id :markdown-editor))))
    (is (not (packaging/owner-enabled? settings by-id :blog-editor)))
    (is (not (packaging/owner-enabled? settings by-id :markdown-editor)))
    (is (packaging/owner-enabled? settings by-id :markdown-components))
    (is (packaging/owner-enabled? {:modules {:blog-editor {:enabled false}}} by-id :markdown-editor))
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


(deftest installed-module-styles-are-absolute-asset-paths
  (let [found (modules/all-declarations)
        experiments (first (filter #(= :test (:id %)) found))]
    (is (= ["/css/website/modules/test.min.css"] (:styles experiments))
        "Experiments publish the real stylesheet as an absolute asset path")
    (doseq [{:keys [id styles]} found, path styles]
      (is (string/starts-with? path "/") (str id " must supply a root-relative asset path")))))

(deftest declaration-and-runtime-contract-reject-relative-stylesheet-paths
  (let [file (java.io.File/createTempFile "module-style-path" ".cljs")
        schema (dsl/->malli declarations/module)]
    (try
      (doseq [path ["test" "" "css/test.css" "https://example.com/test.css"]]
        (spit file (str "(ns example.module)\n(def spec {:styles [" (pr-str path) "]})"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"absolute stylesheet asset paths"
                             (modules/declaration file)))
        (is (not (m/validate schema {:id :example, :styles [path]}))))
      (spit file "(ns example.module)\n(def spec {:styles [\"/css/custom.css\"]})")
      (is (= ["/css/custom.css"] (:styles (modules/declaration file))))
      (is (m/validate schema {:id :example, :styles ["/css/custom.css"]}))
      (is (m/validate schema {:id :example, :styles []}))
      (finally (.delete file)))))
