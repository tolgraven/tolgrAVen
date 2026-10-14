(ns website.github-settings-test
  (:require [clojure.test :refer [deftest is]]
            [website.config :as config]
            [website.content.service :as content]
            [website.content.contract :as contract]
            [website.modules.github.settings :as settings]
            [website.modules.github.sync :as sync]))

(deftest explicit-selection-is-shared-and-never-requests-unpublished-content
  (with-redefs [config/env {:github-user "Public-Owner"
                           :github-repo "Public-Repo"
                           :github-token "private-token"}
                content/bundle! (fn [& _] (throw (Exception. "Must not request CMS")))]
    (is (= {:user "Public-Owner", :repo "Public-Repo"} (settings/selection!)))
    (is (= {:owner "public-owner", :repo "public-repo"} (sync/target!)))
    (is (= {:settings {:user "Public-Owner", :repo "Public-Repo"}}
           (:body (settings/response))))
    (is (not (.contains (pr-str (settings/response)) "private-token")))))

(deftest missing-settings-are-a-successful-unconfigured-state
  (with-redefs [config/env {}
                content/bundle! (fn [& _] (throw (Exception. "Must not request CMS")))]
    (is (nil? (sync/target!)))
    (is (= 200 (:status (settings/response))))
    (is (= {:settings nil} (:body (settings/response))))))

(deftest selected-config-is-not-a-stale-content-cache-entry
  (doseq [repo ["first" "second" "first"]]
    (with-redefs [config/env {:github-user "owner", :github-repo repo}]
      (is (= repo (get-in (settings/response) [:body :settings :repo]))))))

(deftest invalid-settings-fail-locally-without-gating-the-shell
  (let [original @content/*immediate]
    (try
      (doseq [configuration [{:github-user "owner"}
                             {:github-repo "repo"}
                             {:github-user "../bad", :github-repo "repo"}
                             {:github-user "owner", :github-repo ".."}]]
        (with-redefs [config/env configuration
                      content/settings (constantly {})]
          (is (= 503 (:status (settings/response))))
          (is (= "no-store" (get-in (settings/response) [:headers "Cache-Control"])))
          (content/refresh-immediate!)
          (is (map? (content/immediate-content)))
          (is (= 200 (:status (content/response [:document]))))))
      (finally (reset! content/*immediate original)))))

(deftest future-cms-ownership-requires-a-declaration-and-keeps-failures-visible
  (with-redefs [config/env {}
                contract/sections (conj contract/sections :github-settings)
                content/bundle! (fn [ks]
                                  (is (= [:github-settings] ks))
                                  {:content {:github-settings {:user "cms-owner", :repo "cms-repo"}}})]
    (is (= {:user "cms-owner", :repo "cms-repo"} (settings/selection!))))
  (with-redefs [config/env {}
                contract/sections (conj contract/sections :github-settings)
                content/bundle! (fn [& _] (throw (Exception. "CMS unavailable")))]
    (is (= 503 (:status (settings/response))))))
