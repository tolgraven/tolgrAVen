(ns tolgraven.build-modules-test
  (:require [clojure.test :refer [deftest is testing]]
            [tolgraven.build.modules :as modules]
            [tolgraven.build.site :as site]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(deftest module-build-and-runtime-catalog-have-one-owner
  (let [found (modules/discover)
        by-id (into {} (map (juxt :id identity)) found)]
    (is (every? #(contains? by-id %) [:blog :cv :docs :test :user :link-preview :carousel :contact :supabase]))
    (is (not (contains? by-id :main)))
    (is (= 'tolgraven.modules.experiments.module (get-in by-id [:test :entry])))
    (is (= #{:main :user :link-preview} (get-in by-id [:blog :depends-on])))
    (doseq [id [:cv :home]]
      (is (= #{:main :carousel} (get-in by-id [id :depends-on]))))
    (is (= #{:main :maps :carousel} (get-in by-id [:strava :depends-on])))
    (doseq [id [:carousel :contact]]
      (is (= #{:main} (get-in by-id [id :depends-on]))))
    (is (= #{:main} (get-in by-id [:user :depends-on])))
    (is (= #{:main :markdown} (get-in by-id [:link-preview :depends-on])))
    (is (= (set (keys by-id)) (set (keys (modules/bundles)))))))

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
        env {:ns {:name 'tolgraven.catalog-fixture}}
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
        (is (some #{"tolgraven/modules/main/module.cljs"} resources))
        (is (some #{"tolgraven/components/shell.cljs"} resources))
        (is (some #{"tolgraven/modules/user/views.cljc"} resources))
        (is (some #{"/css/tolgraven/modules/icons-user.min.css"} (:paths (:user styles))))
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
