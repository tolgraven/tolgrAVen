(ns tolgraven.supabase-client-test
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [ajax.core :as ajax]
            [clojure.string :as string]
            [reagent.ratom :as ratom]
            [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [tolgraven.service-status :as status]
            [tolgraven.supabase.client :as client]
            [tolgraven.supabase.realtime :as realtime]))

(defn tick! [] (js/Promise. (fn [resolve _] (js/setTimeout resolve 0))))

(defn await-value! [reference predicate]
  ;; SDK completion queues a re-frame event. Observe the delivered value rather
  ;; than assuming that one zero-delay timer also commits that event.
  (js/Promise.
    (fn [resolve reject]
      (let [deadline (+ (.now js/performance) 2000)]
        (letfn [(check! []
                  (ratom/flush!)
                  (cond
                    (predicate @reference) (resolve nil)
                    (> (.now js/performance) deadline) (reject (js/Error. "Query value did not settle"))
                    :else (js/requestAnimationFrame check!)))]
          (check!))))))

(defn mock-client []
  (let [*rows (atom {}) *selects (atom []) *filters (atom []) *channels (atom {})
        *removed (atom []) *auth-change (atom nil) *deferred (atom nil)
        auth #js {:getSession #(js/Promise.resolve #js {:data #js {:session @client/*session}})
                  :onAuthStateChange (fn [callback]
                                       (reset! *auth-change callback)
                                       #js {:data #js {:subscription #js {:unsubscribe (fn [])}}})}
        sdk #js {:auth auth
                 :from (fn [table]
                         (let [*columns (atom [])
                               q #js {}]
                           (aset q "select" (fn [columns] (reset! *columns (mapv keyword (string/split columns #","))) q))
                           (aset q "order" (fn [_] q))
                           (doseq [op ["eq" "is" "in"]]
                             (aset q op (fn [field value]
                                          (swap! *filters conj [table field op (if (= op "in") (vec value) value)]) q)))
                           (aset q "range"
                             (fn [start end]
                               (swap! *selects conj [table start end])
                               (-> (or @*deferred
                                       (js/Promise.resolve
                                        (clj->js {:data (vec (take (inc (- end start)) (drop start (get @*rows table []))))})))
                                   (.then (fn [reply]
                                            ;; PostgREST includes every selected column, including
                                            ;; SQL NULLs. Keep extra fields to test the allowlist.
                                            (let [result (js->clj reply :keywordize-keys true)]
                                              (clj->js
                                               (update result :data
                                                 #(mapv (fn [row] (merge (zipmap @*columns (repeat nil)) row)) %)))))))))
                           q))
                 :channel (fn [name]
                            (let [*change (atom nil) *status (atom nil)
                                  channel #js {}]
                              (aset channel "on" (fn [_ _ callback] (reset! *change callback) channel))
                              (aset channel "subscribe" (fn [callback] (reset! *status callback) channel))
                              (swap! *channels assoc name {:channel channel :change *change :status *status})
                              channel))
                 :removeChannel (fn [channel] (swap! *removed conj channel) (js/Promise.resolve "ok"))}]
    {:sdk sdk :rows *rows :filters *filters :selects *selects :channels *channels :removed *removed
     :auth-change *auth-change :deferred *deferred}))

(defn reset-client! [mock]
  (doseq [entry (vals @client/*tables)]
    (when-let [monitor (some-> entry :*connection deref)] ((:close! monitor)))
    (when @(:*retry entry) (js/clearTimeout @(:*retry entry))))
  (reset! client/*queries {})
  (reset! client/*tables {})
  (reset! client/*session nil)
  (reset! client/*loaded #{})
  (reset! client/*seed realtime/empty-seed)
  (reset! client/*client (:sdk mock)))

(defonce *restore-test (atom nil))
(use-fixtures :each
  {:before (fn []
             (let [restore-db! (rf/make-restore-fn)
                   snapshots (mapv (fn [state] [state @state])
                                   [client/*client client/*settings client/*session
                                    client/*seed client/*loaded client/*auth-listener
                                    client/*preloads status/*failures])]
               (reset! *restore-test
                 (fn []
                   ;; Dispose through the adapter even when an assertion/callback
                   ;; fails before the test's happy-path disposal is reached.
                   (doseq [entry (vals @client/*queries)]
                     (ratom/dispose! (:*state entry)))
                   (when-let [listener @client/*auth-listener] (.unsubscribe listener))
                   (when-let [tick @client/*query-tick] (js/clearTimeout tick))
                   (reset! client/*query-tick nil)
                   (reset-client! {:sdk nil})
                   (doseq [[state value] snapshots] (reset! state value))
                   (restore-db!)))))
   :after (fn [] (when-let [restore! @*restore-test] (restore!))
                  (reset! *restore-test nil))})

(defn status! [mock name status]
  (client/drain-queries!)
  (@(:status (get @(:channels mock) name)) status nil))
(defn change! [mock name change]
  (@(:change (get @(:channels mock) name)) (clj->js change)))

(deftest queries-share-a-stream-and-do-not-refetch-after-row-events
  (async done
    (let [mock (mock-client)]
      (reset-client! mock)
      (reset! (:rows mock) {"site_users" [{:id "u" :name "Before" :email "private"}]})
      (let [all (client/ensure-query! {:path-collection [:users]})
            one (client/ensure-query! {:path-document [:users :u]})]
        (is (empty? @(:channels mock)) "Streams join the next batch")
        (client/drain-queries!)
        (is (= 1 (count @(:channels mock))))
        (is (empty? @(:selects mock)))
        (status! mock "store-site_users" "SUBSCRIBED")
        (-> (tick!)
            (.then (fn []
                     (is (= "Before" (get-in @one [:data :name])))
                     (is (nil? (get-in @one [:data :email])))
                     (change! mock "store-site_users" {:table "site_users" :eventType "UPDATE" :new {:id "u" :name "After"}})
                     (tick!)))
            (.then (fn []
                     (ratom/flush!)
                     (is (= "After" (get-in @rfdb/app-db [:store :snapshot :seed :users 0 :name])))
                     (is (= "After" (get-in @one [:data :name])))
                     (is (= 1 (count (:docs @all))))
                     (is (= 1 (count @(:selects mock))))
                     (change! mock "store-site_users" {:table "site_users" :eventType "DELETE" :old {:id "u"}})
                     (tick!)))
            (.then (fn []
                     (ratom/flush!)
                     (is (nil? @one))
                     (is (= [] (:docs @all)))
                     ;; Rejoining fetches once to catch changes missed offline.
                     (reset! (:rows mock) {"site_users" [{:id "v" :name "Offline insert"}]})
                     (status! mock "store-site_users" "SUBSCRIBED")
                     (await-value! all #(= "v" (get-in % [:docs 0 :id])))))
            (.then (fn []
                     (ratom/flush!)
                     (is (= "v" (get-in @all [:docs 0 :id])))
                     (is (= 2 (count @(:selects mock))))
                     (ratom/dispose! one)
                     (is (empty? @(:removed mock)))
                     (ratom/dispose! all)
                     (is (= 1 (count @(:removed mock))))
                     (is (empty? @client/*tables))))
            (.catch #(is false (str %)))
            (.finally done))))))

(deftest writes-during-paginated-snapshots-are-replayed
  (async done
    (let [mock (mock-client) *resolve (atom nil)]
      (reset-client! mock)
      (reset! (:deferred mock) (js/Promise. (fn [resolve _] (reset! *resolve resolve))))
      (let [one (client/ensure-query! {:path-document [:users :u]})]
        (status! mock "store-site_users" "SUBSCRIBED")
        (change! mock "store-site_users" {:table "site_users" :eventType "UPDATE" :new {:id "u" :name "Live"}})
        (@*resolve #js {:data #js [#js {:id "u" :name "Stale snapshot"}]})
        (-> (tick!)
            (.then (fn []
                     (is (= "Live" (get-in @one [:data :name])))
                     (is (= 1 (count @(:selects mock))))
                     (ratom/dispose! one)))
            (.catch #(is false (str %)))
            (.finally done))))))

(deftest stable-pagination-loads-more-than-one-api-page
  (async done
    (let [mock (mock-client)]
      (reset-client! mock)
      (reset! (:rows mock) {"site_users" (mapv #(hash-map :id (str %) :name (str %)) (range 501))})
      (let [all (client/ensure-query! {:path-collection [:users]})]
        (status! mock "store-site_users" "SUBSCRIBED")
        (-> (tick!)
            (.then (fn []
                     (is (= 501 (count (:docs @all))))
                     (is (= [["site_users" 0 499] ["site_users" 500 999]] @(:selects mock)))
                     (ratom/dispose! all)))
            (.catch #(is false (str %)))
            (.finally done))))))

(deftest removed-subscriptions-cannot-install-late-snapshots
  (async done
    (let [mock (mock-client) *resolve (atom nil)]
      (reset-client! mock)
      (reset! (:deferred mock) (js/Promise. (fn [resolve _] (reset! *resolve resolve))))
      (let [all (client/ensure-query! {:path-collection [:users]})]
        (status! mock "store-site_users" "SUBSCRIBED")
        (ratom/dispose! all)
        (@*resolve #js {:data #js [#js {:id "u" :name "Must not install"}]})
        (-> (tick!)
            (.then (fn []
                     (is (= [] (:users @client/*seed)))
                     (is (empty? @client/*loaded))))
            (.catch #(is false (str %)))
            (.finally done))))))

(deftest account-switch-clears-private-documents-and-rejects-late-responses
  (async done
    (let [mock (mock-client) *resolve (atom nil)
          profiles (atom [])
          sdk-before (.-supabase js/globalThis)]
      (reset-client! mock)
      (set! (.-supabase js/globalThis) #js {:createClient (fn [& _] (:sdk mock))})
      (client/init! {:url "https://example.invalid" :anon-key "public"} #(swap! profiles conj %) (fn [_]))
      (reset! (:deferred mock) (js/Promise. (fn [resolve _] (reset! *resolve resolve))))
      (let [private (client/ensure-query! {:path-collection [:gpt-threads]})]
        (is (empty? @(:channels mock)))
        (@(:auth-change mock) "SIGNED_IN" #js {:user #js {:id "a"}})
        (-> (tick!)
            (.then (fn []
                     (status! mock "store-user_documents" "SUBSCRIBED")
                     (swap! client/*seed assoc :store_documents [{:owner_id "a" :collection "gpt-threads" :doc_id "t" :data {:messages ["secret"]}}])
                     (swap! client/*loaded conj "user_documents")
                     (tick!)))
            (.then (fn []
                     (is (= 1 (count (:docs @private))))
                     (@(:auth-change mock) "SIGNED_OUT" nil)
                     (ratom/flush!)
                     (is (empty? (:docs @private)) "Auth change immediately hides private data")
                     (is (= [] (:store_documents @client/*seed)))
                     (@*resolve #js {:data #js [#js {:owner_id "a" :collection "gpt-threads" :doc_id "t" :data #js {:messages #js ["late secret"]}}]})
                     (tick!)))
            (.then (fn []
                     (is (= [] (:store_documents @client/*seed)))
                     (is (not (contains? @client/*loaded "user_documents")))
                     (ratom/dispose! private)))
            (.catch #(is false (str %)))
            (.finally (fn [] (set! (.-supabase js/globalThis) sdk-before) (done))))))))

(deftest private-one-off-reads-do-not-return-after-an-account-switch
  (async done
    (let [mock (mock-client) *resolve (atom nil) *results (atom [])]
      (reset-client! mock)
      (reset! client/*session #js {:user #js {:id "a"}})
      (reset! (:deferred mock) (js/Promise. (fn [resolve _] (reset! *resolve resolve))))
      (client/read-once! {:path-collection [:gpt-threads]} #(swap! *results conj %) #(swap! *results conj %))
      (reset! client/*session #js {:user #js {:id "b"}})
      (@*resolve #js {:data #js [#js {:owner_id "a" :collection "gpt-threads" :doc_id "t" :data #js {:messages #js ["secret"]}}]})
      (-> (tick!)
          (.then (fn [] (is (empty? @*results))))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest authenticated-responses-cannot-cross-profile-link-changes
  (async done
    (let [mock (mock-client) callbacks (atom nil) responses (atom [])
          original-get ajax/GET
          session (fn [profile] #js {:access_token "token" :user #js {:id "same-account" :app_metadata #js {:site_user_id profile}}})]
      (reset-client! mock)
      (reset! client/*session (session "old-profile"))
      (set! ajax/GET (fn [_ opts] (reset! callbacks opts)))
      (client/authenticated-request! :get "/api/profile" nil
                                     #(swap! responses conj [:success %])
                                     #(swap! responses conj [:error %]))
      (-> (tick!)
          (.then (fn []
                   (is (some? @callbacks))
                   ;; A token refresh for the same owner still accepts its reply.
                   (reset! client/*session (session "old-profile"))
                   ((:handler @callbacks) :fresh)
                   (is (= [[:success :fresh]] @responses))
                   (reset! responses [])
                   (reset! client/*session (session "new-profile"))
                   ((:handler @callbacks) :stale)
                   ((:error-handler @callbacks) :stale)
                   (is (empty? @responses))
                   (reset! client/*session (session "old-profile"))
                   (reset! client/*client (:sdk (mock-client)))
                   ((:handler @callbacks) :stale-client)
                   (is (empty? @responses))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (set! ajax/GET original-get) (done)))))))

(deftest stream-failure-reports-and-still-loads-http-content
  (async done
    (let [mock (mock-client) failures @status/*failures]
      (reset-client! mock)
      (reset! status/*failures {})
      (reset! (:rows mock) {"site_users" [{:id "u" :name "Available over HTTP"}]})
      (let [all (client/ensure-query! {:path-collection [:users]})]
        (status! mock "store-site_users" "TIMED_OUT")
        (-> (tick!)
            (.then (fn []
                     (is (contains? @status/*failures [:supabase-stream "site_users"]))
                     (is (= "Available over HTTP" (get-in @all [:docs 0 :data :name])))
                     (status! mock "store-site_users" "SUBSCRIBED")
                     (tick!)))
            (.then (fn [] (is (empty? @status/*failures))))
            (.catch #(is false (str %)))
            (.finally (fn [] (ratom/dispose! all) (reset! status/*failures failures) (done))))))))

(deftest snapshot-error-notifies-retains-data-and-clears-after-recovery
  (async done
    (let [mock (mock-client) failures @status/*failures]
      (reset-client! mock)
      (reset! status/*failures {})
      (reset! (:rows mock) {"site_users" [{:id "u" :name "Last good value"}]})
      (let [all (client/ensure-query! {:path-collection [:users]})]
        (status! mock "store-site_users" "SUBSCRIBED")
        (-> (tick!)
            (.then (fn []
                     (reset! (:deferred mock) (js/Promise.resolve #js {:error #js {:message "private upstream details"}}))
                     (status! mock "store-site_users" "SUBSCRIBED")
                     (tick!)))
            (.then (fn []
                     (is (contains? @status/*failures [:supabase-load "site_users"]))
                     (is (= "Last good value" (get-in @all [:docs 0 :data :name])))
                     (reset! (:deferred mock) nil)
                     (status! mock "store-site_users" "SUBSCRIBED")
                     (tick!)))
            (.then (fn [] (is (empty? @status/*failures))))
            (.catch #(is false (str %)))
            (.finally (fn [] (ratom/dispose! all) (reset! status/*failures failures) (done))))))))

(deftest stalled-service-request-rejects-within-deadline
  (async done
    (-> (status/within! (js/Promise. (fn [_ _])) 10)
        (.then (fn [_] (is false "A hung request must reject")))
        (.catch (fn [error] (is (= "Service request timed out" (.-message error)))))
        (.finally done))))

(deftest auth-session-initialization-error-is-visible
  (async done
    (let [mock (mock-client) failures @status/*failures
          before (.-supabase js/globalThis)]
      (reset-client! mock)
      (reset! status/*failures {})
      (aset (.-auth ^js (:sdk mock)) "getSession"
            #(js/Promise.resolve #js {:error #js {:message "Do not expose raw details"}}))
      (set! (.-supabase js/globalThis) #js {:createClient (fn [& _] (:sdk mock))})
      (client/init! {:url "https://example.invalid" :anon-key "public"} (fn [_]) (fn [_]))
      (-> (tick!)
          (.then (fn []
                   (is (= "Supabase session unavailable" (get-in @status/*failures [:supabase-session :title])))
                   (is (not (.includes (pr-str @status/*failures) "Do not expose raw details")))))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! (.-supabase js/globalThis) before)
                      (reset! status/*failures failures) (done)))))))

(deftest preload-populates-subscription-cache-without-live-channels
  (async done
    (let [mock (mock-client) opts {:path-document [:users :u]}]
      (reset-client! mock)
      (reset! (:rows mock) {"site_users" [{:id "u" :name "Preloaded"}]})
      (let [a (client/preload-query! opts) b (client/preload-query! opts)]
        (is (identical? a b))
        (is (empty? @(:selects mock)))
        (-> a
            (.then (fn [value]
                     (is (= "Preloaded" (get-in value [:data :name])))
                     (is (= 1 (count @(:selects mock))))
                     (is (empty? @(:channels mock)))
                     (let [subscription (client/ensure-query! opts)]
                       (is (= "Preloaded" (get-in @subscription [:data :name])))
                       (ratom/dispose! subscription))
                     (tick!)))
            (.then (fn [_]
                     (is (empty? @(:channels mock)) "Disposed before the tick, so no channel starts")
                     (is (:ready? (client/cached-query opts)))
                     (client/preload-query! opts)))
            (.then (fn [_] (is (= 1 (count @(:selects mock))) "Cache survives reader cleanup")))
            (.catch #(is false (str %)))
            (.finally done))))))

(deftest bounded-comment-read-and-reply-counts-use-two-bulk-requests
  (async done
    (let [mock (mock-client)]
      (reset-client! mock)
      (reset! (:rows mock) {"blog_comments" (mapv #(hash-map :id (str %) :parent_post 42 :ts %) (range 50))})
      (client/read-once! {:scoped? true :reply-counts? true :path-collection [:blog-comments]
                         :where [[:parent-post :== 42] [:parent-comment :== nil]]
                         :order-by [[:ts :desc] [:id :desc]] :limit 11}
        (fn [result]
          (is (= 11 (count (:docs result))))
          (is (= [["blog_comments" 0 10] ["blog_comments" 0 499]] @(:selects mock)))
          (is (= 1 (count (filter #(= "in" (nth % 2)) @(:filters mock)))))
          (is (= 11 (count (last (last @(:filters mock))))) "One IDs query counts all replies")
          (done))
        (fn [error] (is false (str error)) (done))))))
