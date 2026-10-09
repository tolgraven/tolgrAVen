(ns tolgraven.page-coercion-test
  (:require [cljs.test :refer-macros [deftest is]]
            [malli.core :as m]
            [malli.registry :as registry]
            [reitit.coercion :as coercion]
            [reitit.frontend :as frontend]
            [tolgraven.navigation.routes :as routes]
            [tolgraven.schema.http :as schemas]
            [tolgraven.validation.runtime :as validation]))

(deftest production-registry-retains-owned-contracts
  (is (= "custom" registry/type) "Browser tests exercise the release registry")
  (is (nil? (registry/-schema m/default-registry :function)))
  (is (m/validate [:cat :keyword [:? :int] [:* :string]] [:event 2 "a" "b"]))
  (is (not (m/validate [:cat :keyword [:? :int] [:* :string]] [:event "a" 2]))))

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
