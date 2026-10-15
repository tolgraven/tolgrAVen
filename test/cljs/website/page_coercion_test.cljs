(ns website.page-coercion-test
  (:require [cljs.test :refer-macros [deftest is]]
            [malli.core :as m]
            [website.schema.dsl :as dsl]
            [malli.registry :as registry]
            [reitit.coercion :as coercion]
            [reitit.frontend :as frontend]
            [website.navigation.routes :as routes]
            [website.modules.github.configuration-schema :as github-configuration]
            [website.modules.github.pages :as github-pages]
            [website.schema.http :as schemas]
            [website.schema.page-coercion :as page]
            [website.schema.declarations :as declarations]
            [malli.util :as mu]
            [website.validation.runtime :as validation]
            [website.react :as r]))

(deftest production-registry-retains-owned-contracts
  (is (= "custom" registry/type) "Browser tests exercise the release registry")
  (is (nil? (registry/-schema m/default-registry :function)))
  (is (m/validate (dsl/->malli [:cat :keyword [:? :int] [:* :string]]) [:event 2 "a" "b"]))
  (is (not (m/validate (dsl/->malli [:cat :keyword [:? :int] [:* :string]]) [:event "a" 2]))))

(deftest production-registry-retains-github-repository-exclusions
  (let [settings (dsl/->malli github-configuration/result)
        parameters (dsl/->malli github-pages/parameters)
        sha (apply str (repeat 40 "a"))]
    (doseq [repo ["tolgrAVen" "site.config" ".github" "..config"]]
      (is (m/validate settings {:settings {:user "tolgraven", :repo repo}}))
      (is (m/validate parameters {:user "tolgraven", :repo repo, :sha sha})))
    (doseq [repo ["." ".."]]
      (is (not (m/validate settings {:settings {:user "tolgraven", :repo repo}})))
      (is (not (m/validate parameters {:user "tolgraven", :repo repo, :sha sha}))))))

(deftest route-coercion-remains-active-with-internal-checks-disabled
  (let [enabled? @validation/*enabled?]
    (try
      (reset! validation/*enabled? false)
      (let [match (frontend/match-by-path routes/router
                    "/blog/page/2?userBox=false&settingsBox=true&code=oauth-token&debug=1")]
        (is (= {:nr 2} (get-in match [:parameters :path])))
        (is (= {:userBox false :settingsBox true :code "oauth-token" :debug "1"}
               (get-in match [:parameters :query]))))
      (doseq [path ["/blog/page/0" "/blog/page/1000000" "/blog/page/zero"
                    "/blog?userBox=invalid"]]
        (is (thrown? js/Error (frontend/match-by-path routes/router path))))
      (finally (reset! validation/*enabled? enabled?)))))

(deftest query-encoding-keeps-false-and-extra-parameters
  (let [encode (coercion/-query-string-coercer schemas/coercion schemas/page-query)]
    (is (= {:userBox false :settingsBox true :code "oauth-token" :tag ["a" "b"]}
           (encode {:userBox false :settingsBox true :code "oauth-token" :tag ["a" "b"]}
                   nil)))
    (let [match (frontend/match-by-name routes/router :blog)
          path (frontend/match->path match {:userBox false :settingsBox true
                                           :code "oauth-token"})
          round-trip (frontend/match-by-path routes/router path)]
      (is (= {:userBox false :settingsBox true :code "oauth-token"}
             (get-in round-trip [:parameters :query]))))))

(deftest coercion-errors-report-constraints-without-values
  (let [decode (coercion/-request-coercer schemas/coercion :string schemas/blog-page)
        error (decode {:nr "private-invalid-value"} nil)
        encoded (coercion/-encode-error schemas/coercion error)]
    (is (coercion/error? error))
    (is (= #{:humanized} (set (keys encoded))))
    (is (.includes (pr-str encoded) "should be an integer"))
    (is (not (.includes (pr-str encoded) "private-invalid-value")))))

(deftest composed-schemas-stay-data-until-interpreted
  (let [base [:map [:nested [:map [:base :int] [:refined :string]]]]
        extra [:map [:nested [:map [:extension {:optional true} :boolean]
                                  [:refined :int]]]]
        schema (declarations/compose base extra)
        eager (mu/merge base extra)]
    (is (= :merge (first schema)))
    (doseq [value [{:nested {:base 1 :refined 2}}
                   {:nested {:base 1 :refined 2 :extension false :other "kept"}}
                   {:nested {:base 1 :refined "invalid"}}
                   {:nested {:refined 2}}]]
      (is (= (m/validate (dsl/->malli eager) value) (m/validate (dsl/->malli schema) value))))))

(deftest stable-router-dispatchers-acquire-the-adapter-later
  (let [adapter @page/*adapter]
    (try
      (page/install! nil)
      (let [raw (frontend/match-by-path routes/router "/blog/page/2?userBox=false&extra=kept")]
        (is (= "2" (get-in raw [:parameters :path :nr])))
        (is (= "false" (get-in raw [:parameters :query :userBox])))
        (is (= {:path {:nr 2} :query {:userBox false :extra "kept"}}
               (routes/initial-parameters raw
                 {:path "/blog/page/2" :query-params {:userBox "false" :extra "kept"}
                  :route-parameters {:path {:nr 2} :query {:userBox false :extra "kept"}}}
                 true true)))
        (is (nil? (routes/initial-parameters raw
                    {:path "/blog/page/2" :route-parameters {:path {:nr 2}}} true true)))
        (page/install! adapter)
        (is (= 2 (get-in (coercion/coerce! raw) [:path :nr])))
        (is (false? (get-in (coercion/coerce! raw) [:query :userBox])))
        (is (= "kept" (get-in (coercion/coerce! raw) [:query :extra]))))
      (finally (page/install! adapter)))))

(deftest history-error-rethrow-is-redacted-and-supersedes-pending-navigation
  (let [navigation @routes/*navigation
        request @routes/*route-request
        *reports (atom [])
        decode (coercion/-request-coercer schemas/coercion :string schemas/page-query)
        error (ex-info "Request coercion failed"
                       (into {} (decode {:userBox "private-invalid-value"} nil)))]
    (try
      (r/with-framework-bindings [r/dispatch #(swap! *reports conj %)]
        (try
          (routes/history-coercion-failed! {:path "/blog"} error)
          (is false "History must receive a redacted exception")
          (catch js/Error failure
            (is (= {:type :validation/route} (ex-data failure)))
            (is (not (.includes (str failure) "private-invalid-value")))))
        (is (> @routes/*navigation navigation))
        (is (> @routes/*route-request request))
        (is (not (.includes (pr-str @*reports) "private-invalid-value"))))
      (finally
        (reset! routes/*navigation navigation)
        (reset! routes/*route-request request)))))
