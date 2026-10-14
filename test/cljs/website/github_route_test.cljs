(ns website.github-route-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [clojure.string :as string]
            [cljs.test :refer-macros [deftest is async]]
            [reitit.frontend :as reitit]
            [website.react :as r]
            [website.component.data]
            [website.events]
            [website.subs]
            [website.modules.github.events]
            [website.modules.github.subs]
            [website.modules.github.model :as model]
            [website.navigation.routes :as routes]
            [website.test-support :as support]))

(defn- match [sha]
  (assoc (reitit/match-by-path routes/router (str "/github/commits/" sha))
         :parameters {:path {:sha sha}, :query {}}))

(deftest component-destination-retains-home-data-and-rejects-stale-loader-completion
  (async done
    (let [restore! (r/make-restore-fn)
          *dispatches (atom [])
          commit (match "abcdef123456")]
      (r/reg-fx :dispatch #(swap! *dispatches conj %))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (is (= :home (get-in commit [:data :module])))
            (is (some #{:intro} (:keys (first (routes/landing-dependencies commit))))
                "A component destination acquires its Home page's native content")
            (r/dispatch-sync [:component-data/install [:common/route] commit])
            (is (= :home (await! (support/subscription-value! [:common/page-id]))))
            (reset! *dispatches [])
            (r/dispatch-sync [:navigation/component-loaded commit])
            (is (= [[:github/navigate-commit "abcdef123456"]] @*dispatches))
            (reset! *dispatches [])
            (r/dispatch-sync [:component-data/install [:common/route] (match "bbbbbbb12345")])
            (r/dispatch-sync [:navigation/component-loaded commit])
            (is (empty? @*dispatches) "A late module completion cannot activate the previous destination"))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))

(deftest direct-sha-navigation-does-not-require-the-latest-page-and-overlap-is-deduplicated
  (async done
    (let [restore! (r/make-restore-fn)
          *requests (atom [])
          prefix "abcdef1"
          full (str prefix (apply str (repeat 33 "2")))
          item {:sha full
                :commit {:message "fix: An old [literal] commit"
                         :author {:date "2001-01-01T12:00:00Z"}}
                :files [{:filename "old.clj", :patch "+ old code"}]}]
      (r/reg-fx :http-xhrio #(swap! *requests conj %))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (r/dispatch-sync [:component-data/install [:common/route] (match prefix)])
            (r/dispatch-sync [:github/navigate-commit prefix])
            (await! (support/settle!))
            (is (empty? @*requests) "The destination waits for the normal repository configuration binding")
            (r/dispatch-sync [:component-data/install [:module :github :configuration]
                              {:settings {:user "route-user", :repo "repo"}}])
            (r/dispatch-sync [:github/init])
            (await! (support/settle!))
            (r/dispatch-sync [:github/init])
            (await! (support/settle!))
            (is (= 2 (count @*requests)) "Pending direct lookup and archive requests are deduplicated")
            (is (not-any? #(get-in % [:url-params :page]) @*requests)
                "No latest-30 page is needed for a deep commit")
            (let [request (first (filter #(string/ends-with? (:uri %) prefix) @*requests))]
              (is (some? request))
              (is (= (str "/api/github/commits/" prefix) (:uri request)))
              (is (= {:user "route-user", :repo "repo"} (:url-params request)))
              (r/dispatch-sync (conj (:on-success request) item)))
            (await! (support/settle!))
            (is (= prefix (await! (support/subscription-value! [:form-field [:github :search]]))))
            (is (= [full] (mapv :sha (await! (support/subscription-value! [:github/filter-by])))))
            (is (not (contains? (first (await! (support/subscription-value! [:github/commits]))) :files))
                "The list cache does not duplicate the full diff")
            (is (= item (await! (support/subscription-value! [:github/commit full]))))
            (r/dispatch-sync [:github/fetch-commit "route-user" "repo" full])
            (await! (support/settle!))
            (is (= 2 (count @*requests)) "A short SHA lookup also populates its canonical detail cache")
            (let [archive (first (filter #(= "/api/github/commits" (:uri %)) @*requests))]
              (is (= prefix (get-in archive [:url-params :q])))
              (r/dispatch-sync (conj (:on-success archive) {:commits [item], :total 1, :complete true})))
            (await! (support/settle!))
            (is (= [full] (mapv :sha (await! (support/subscription-value! [:github/commits])))))
            (doseq [query ["[literal]" "AN OLD" "["]]
              (r/dispatch-sync [:github/search query])
              (await! (support/wait-for! #(some (fn [request] (= query (get-in request [:url-params :q]))) @*requests)))
              (let [archive (last (filter #(= query (get-in % [:url-params :q])) @*requests))]
                (is (= "/api/github/commits" (:uri archive)))
                (r/dispatch-sync (conj (:on-success archive) {:commits [item], :total 1, :complete true})))
              (await! (support/settle!))
              (is (= 1 (count (await! (support/subscription-value! [:github/filter-by]))))
                  "Archived query responses retain safe literal preview filtering"))
            (r/dispatch-sync [:github/search prefix])
            (await! (support/settle!))
            (is (= [full] (mapv :sha (await! (support/subscription-value! [:github/commits]))))
                "A cached direct SHA remains visible immediately across archive query changes")
            (await! (support/wait-for! #(= prefix (get-in (last @*requests) [:url-params :q])))))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))

(deftest failed-target-lookup-clears-pending-and-can-retry
  (async done
    (let [restore! (r/make-restore-fn)
          *requests (atom [])]
      (r/reg-fx :http-xhrio #(swap! *requests conj %))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (r/dispatch-sync [:component-data/install [:module :github :configuration]
                              {:settings {:user "retry-user", :repo "repo"}}])
            (r/dispatch-sync [:github/init])
            (await! (support/settle!))
            (r/dispatch-sync [:github/search "bbbbbbb"])
            (await! (support/settle!))
            (let [request (last (filter #(string/ends-with? (:uri %) "bbbbbbb") @*requests))]
              (r/dispatch-sync (conj (:on-failure request) {:status 404, :status-text "Not found"})))
            (await! (support/settle!))
            (is (empty? (:commits-pending (await! (support/state-at! [:state :github])))))
            (is (= 404 (:status (await! (support/subscription-value! [:github/commit-error "bbbbbbb"])))))
            (r/dispatch-sync [:github/search "bbbbbbb"])
            (await! (support/settle!))
            (is (= 2 (count (filter #(string/ends-with? (:uri %) "bbbbbbb") @*requests)))
                "A failed arbitrary lookup can be retried")
            (await! (support/wait-for! #(some (fn [request] (= "bbbbbbb" (get-in request [:url-params :q]))) @*requests))))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))

(deftest commit-filter-does-not-execute-user-regex
  (is (model/matches? {:sha "abc1234", :commit {:message "fix: [Literal]"}} "["))
  (is (model/matches? {:sha "abc1234", :commit {:message "fix: [Literal]"}} "ABC1234"))
  (is (not (model/matches? {:sha "bbbbbbb", :commit {:message "mentions abc1234"}} "abc1234"))
      "A SHA destination selects the commit, rather than other commits mentioning it")
  (is (not (model/matches? {:sha "abc1234", :commit {:message "fix: plain"}} ".*"))))
