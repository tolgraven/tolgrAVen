(ns tolgraven.dev-console-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.component :as component]
            [tolgraven.component.data :as data]
            [tolgraven.component.sources]
            [tolgraven.component.registry :as registry]
            [tolgraven.component.instrumentation :as instrumentation]
            [tolgraven.dev-console.capture :as capture]
            [tolgraven.dev-console.state :as state]
            [tolgraven.dev-console.layout :as layout]
            [tolgraven.dev-console.views :as views]
            [tolgraven.dev-console-fixture :as fixture]
            [tolgraven.render-context :as context]
            [tolgraven.react :as rf]
            [tolgraven.test-support :as support]))

(defn <loading> [] [:span "Namespace-owned loading"])
(defc <own-loading> [] [:div [<loading>]])
(defc <shared-name> {:state {}} []
  :let [*count (<sub :comp [:count] {:initial 0})]
  [:button {:on-click #(>update *count inc)} (str "here:" @*count)])

(deftest trace-projection-drops-snapshots-and-console-descendants
  (let [records (capture/trace-records
                  [{:id 1 :op-type :event :tags {:event [:dev-console/clear]}}
                   {:id 2 :child-of 1 :op-type :event/handler :tags {:app-db-after {:secret true}}}
                   {:id 3 :op-type :event :tags {:event [:blog/load] :app-db-after {:secret true}
                                               :reaction (js-obj) :effects {:db {:secret true}}}}])]
    (is (= [3] (mapv :id records)))
    (is (= {:event [:blog/load]} (:tags (first records))))))

(deftest epoch-and-settled-results-retain-only-bounded-diffs
  (let [epoch {:event [:blog/load] :app-db/before {:count 1 :dev-console {:records ["old"]}}
               :app-db/after {:count 2 :dev-console {:records ["new"]}}
               :effects {:db {:count 2 :dev-console {:records ["new"]}} :dispatch [:next]}
               :coeffects {:db {:count 1}}}
        record (capture/epoch-record epoch)
        result (capture/settled-result {:ok? true :root-epoch epoch :cascaded-epochs [epoch]})]
    (is (= {:count 1} (:removed record)))
    (is (= {:count 2} (:added record)))
    (is (= {:dispatch [:next]} (:effects record)))
    (is (nil? (:app-db/after record)))
    (is (nil? (:coeffects record)))
    (is (= record (:root-epoch result)))
    (is (= [record] (:cascaded-epochs result)))
    (is (= 40 (count (capture/preview (range)))))
    (is (= :debug/object (capture/preview (js-obj))))))

(deftest bounded-options-and-data-types
  (is (= 500 (:limit (state/options {}))))
  (is (= 2000 (:limit (state/options {:options {:dev-console {:limit 99999}}}))))
  (is (= [2 3] (state/bounded 2 [1 2] [3])))
  (is (= ["map" "vector" "list" "set" "nil" "boolean" "number" "keyword" "string"]
         (mapv views/value-type [{} [] '(1) #{} nil true 2 :key "text"])))
  (is (= [:common/route] (views/parse-query "[:common/route]")))
  (is (nil? (views/parse-query "{:bad true}")))
  (is (= 2 (views/trace-depth {1 {:id 1} 2 {:id 2 :child-of 1}} {:child-of 2}))))

(deftest namespace-identities-and-ssr-markup
  (is (not= (instrumentation/identity-for (registry/component-spec <shared-name>))
            (instrumentation/identity-for (registry/component-spec fixture/<shared-name>))))
  (is (contains? @registry/*catalog ["tolgraven.dev-console-test" "<shared-name>"]))
  (is (contains? @registry/*catalog ["tolgraven.dev-console-fixture" "<shared-name>"]))
  (let [form ^{:key "stable"} [:article {:class "post"} [:h1 "Title"]]
        result (binding [context/*server?* true]
                 (instrumentation/instrument {:ns "example.views" :name "<post>"} form [] nil))]
    (is (= :article (first result)))
    (is (= "post" (get-in result [1 :class])))
    (is (nil? (get-in result [1 :data-dev-component])))
    (is (= form result))
    (is (= [:h1 "Title"] (last result)))
    (is (= {:key "stable"} (meta result)))))

(deftest mounted-namespace-loading-and-independent-state
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root [:div [<own-loading>] [<shared-name>] [fixture/<shared-name>]]))
              (is (= "Namespace-owned loading" (.-textContent (.querySelector element "span"))))
              (.click (.querySelector element "button"))
              (await! (support/settle!))
              (is (= ["here:1" "other:0"]
                     (mapv #(.-textContent %) (array-seq (.querySelectorAll element "button")))))
              (finally (support/unmount! root) (.remove element)))))
        (.catch #(is false (str %))) (.finally done))))

(deftest hydration-marker-expiry-is-token-safe
  (async done
    (-> (go-promise
          (let [consumer (await! (support/mount-subscriptions!
                                   {:token [:dev-console/path [:state :debug :hydration-token]]}))
                first-token (random-uuid) second-token (random-uuid)]
            (try
              (rf/dispatch [:dev-console/hydration-start first-token])
              (rf/dispatch [:dev-console/hydration-start second-token])
              (rf/dispatch [:dev-console/hydration-end first-token])
              (await! (support/settle!))
              (is (= second-token (:token ((:values consumer)))))
              (rf/dispatch [:dev-console/hydration-end second-token])
              (await! (support/settle!))
              (is (nil? (:token ((:values consumer)))))
              (finally ((:unmount! consumer))))))
        (.catch #(is false (str %))) (.finally done))))

(deftest dependency-readiness-ignores-unrelated-debug-history
  (async done
    (-> (go-promise
          (let [path [:test :dependency-readiness]
                *renders (atom 0)
                <consumer> (fn []
                             (swap! *renders inc)
                             [:span (pr-str (data/snapshot {:source :app-db :path path}))])
                element (.createElement js/document "div")
                root (await! (support/create-root! element))]
            (.appendChild (.-body js/document) element)
            (try
              (rf/dispatch [:component-data/install path "first"])
              (await! (support/settle!))
              (await! (support/render! root [<consumer>]))
              (let [before @*renders]
                (rf/dispatch [:dev-console/records [{:kind :render :component ["test" "consumer"]}]])
                (await! (support/settle!))
                (is (= before @*renders) "Recording telemetry does not render a dependency consumer")
                (rf/dispatch [:component-data/install path "second"])
                (await! (support/settle!))
                (is (> @*renders before) "Changing the actual dependency renders its consumer")
                (is (re-find #"second" (.-textContent element))))
              (finally (support/unmount! root) (.remove element)
                       (rf/dispatch [:component-data/remove path])))))
        (.catch #(is false (str %))) (.finally done))))

(deftest complete-inline-collections-do-not-hide-data
  (is (views/inline-complete? {:id 27 :title "New features"}))
  (is (views/inline-complete? [0 1 {:ok true}]))
  (is (views/inline-complete? {:empty []}))
  (is (not (views/inline-complete? (vec (range 9)))))
  (is (not (views/inline-complete? {:text (apply str (repeat 60 "x"))})))
  (is (not (views/inline-complete? {:nested {:a 1 :b 2 :c 3}})))
  (is (not (views/inline-complete? {:handler identity})))
  (is (not (views/inline-complete? [{:id 1} {:id 2}])))
  (is (views/map-vector? [{:id 1} {:id 2}]))
  (is (not (views/map-vector? [{:id 1} :other])))
  (is (not (views/inline-complete? (range)))))

(deftest query-and-path-completion-use-real-vectors
  (is (= #{[:blog/post] [:blog/post 27]}
         (set (views/query-suggestions "[:blog/po" {:sub {:blog/post identity :common/route identity}} [[:blog/post 27]]))))
  (is (= 10 (count (views/query-suggestions "" {:sub {}} (mapv #(vector :item %) (range 100))))))
  (is (= {:parent [:state] :needle ":deb"} (views/path-completion "[:state :deb")))
  (is (= {:parent [:state] :needle ""} (views/path-completion "[:state]")))
  (is (= {:parent [] :needle ""} (views/path-completion "[")))
  (is (nil? (views/path-completion "not a vector"))))

(deftest mounted-inline-values-and-query-results
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root [views/<value> [:test :fully-inline] {:id 27 :ok true} 0]))
              (is (nil? (.querySelector element "button")) "Fully visible values cannot expand")
              (is (re-find #":id" (.-textContent element)))
              (await! (support/render! root [views/<query-result> [:dev-console/path [:test :query-result]]]))
              (rf/dispatch [:component-data/install [:test :query-result] {:answer 42}])
              (await! (support/settle!))
              (is (re-find #":answer" (.-textContent element)))
              (is (re-find #"42" (.-textContent element)))
              (await! (support/render! root [views/<query-result> [:nonexistent-subscription]]))
              (is (re-find #"No registered subscription" (.-textContent element)))
              (finally (support/unmount! root) (.remove element)
                       (rf/dispatch [:component-data/remove [:test :query-result]])))))
        (.catch #(is false (str %))) (.finally done))))

(deftest bounded-pagination-and-layout-correlation
  (is (= (vec (range 10 20)) (:items (layout/page (vec (range 25)) 1))))
  (is (= [20 21 22 23 24] (:items (layout/page (vec (range 25)) 999))))
  (is (= (vec (range 20 30)) (:items (layout/page (range) 2))))
  (is (= [] (:items (layout/page [] 2))))
  (let [records [{:kind :epoch :start 1000 :event [:blog/load]}
                 {:kind :render :start 1100 :commit 1150}
                 {:kind :layout :start 1200 :value 0.1 :user-input? true}
                 {:kind :layout :start 1300 :value 0.2 :user-input? false}]
        graph (layout/timeline records nil false)]
    (is (= 2 (count (:shifts graph))))
    (is (= 1 (:delayed graph)))
    (is (= 1 (count (:shifts (layout/timeline records nil true)))))
    (is (= 2 (count (layout/nearby records (last records)))))))

(deftest mounted-pagination-replaces-rows-and-compresses-map-vectors
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root [views/<value> [:test :paged-items] (vec (range 25)) 0]))
              (is (= 10 (.-length (.querySelectorAll element ".dev-value__entry"))))
              (.click (last (array-seq (.querySelectorAll element "button"))))
              (await! (support/settle!))
              (is (= 10 (.-length (.querySelectorAll element ".dev-value__entry"))))
              (is (= "10" (.-textContent (.querySelector element ".dev-value__entry > code"))))
              (await! (support/render! root [views/<value> [:test :map-vector] {:docs [{:id 1} {:id 2}]} 0]))
              (let [preview (.querySelector element ".dev-value__summary")]
                (is (re-find #"\[\{\} × 2\]" (.-textContent preview)))
                (is (= 1 (.-length (.querySelectorAll preview " .dev-value--vector > .dev-value--map > .dev-value__delimiter")))
                    "The vector preview contains one map marker"))
              (finally (support/unmount! root) (.remove element)))))
        (.catch #(is false (str %))) (.finally done))))

(deftest layout-attribution-retains-page-mixed-and-unknown-shifts
  (is (= :inspector (capture/layout-scope [{:inspector? true}])))
  (is (= :page (capture/layout-scope [{:inspector? false}])))
  (is (= :mixed (capture/layout-scope [{:inspector? true} {:inspector? false}])))
  (is (= :unknown (capture/layout-scope []))))

(deftest mounted-layout-graph-selects-shift-details
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                records [{:kind :epoch :start 1000 :event [:blog/load]}
                         {:kind :layout :start 1200 :value 0.1 :user-input? true :scope :page
                          :sources [{:element "HEADER" :inspector? false}]}]]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root [views/<layout-graph> records]))
              (is (= 1 (.-length (.querySelectorAll element "circle"))))
              (.dispatchEvent (.querySelector element "circle") (js/MouseEvent. "click" #js {:bubbles true}))
              (await! (support/settle!))
              (is (re-find #"HEADER" (.-textContent element)))
              (is (re-find #":nearby" (.-textContent element)))
              (finally (support/unmount! root) (.remove element)))))
        (.catch #(is false (str %))) (.finally done))))

(deftest scoped-lookup-resolves-mounted-identities-and-source-queries
  (let [path [:component "blog.views" "<post>" "post-27"]
        depends [{:source :subscription :query [:blog/post 27]}]
        targets (views/scoped-targets "/blog/post/27"
                  {"mounted" {:component ["blog.views" "<post>"] :path path :key "post-27" :depends depends}}
                  [[:blog {:content [:common]}]] [[:component-state/scoped-value (conj path :opts)]])]
    (is (some #(= [:page "/blog/post/27"] (:path %)) targets))
    (is (some #(= [:module :blog] (:path %)) targets))
    (is (= depends (:depends (some #(when (= [:mounted "mounted"] (:id %)) %) targets))))
    (is (some #(= (conj path :opts) (:path %)) targets))
    (is (= [[:blog/post 27]] (views/dependency-queries (first depends))))
    (is (= [[:content [:common]] [:content [:footer]]]
           (views/dependency-queries {:source :strapi :keys [:common :footer]})))))

(deftest timing-values-retain-units-and-load-relative-clocks
  (is (= "1.3 ms" (views/duration-label 1.300000011920929)))
  (is (= "1.865 s" (views/duration-label 1864.752)))
  (is (= "0h 31m 4s 752ms after load" (views/elapsed-label 1864752.300000012)))
  (is (nil? (views/timing-value [:id] 27)))
  (let [value (views/timing-value [:start] 1000)
        [_ attrs timestamp] (last value)]
    (is (= :time (first (last value))))
    (is (= timestamp (:date-time attrs)))
    (is (re-find #"milliseconds.*raw" (get-in value [1 :title])))))

(deftest repeated-event-arguments-share-a-group-with-original-occurrences
  (let [records [{:kind :epoch :event [:page/scrolled 10] :start 100 :end 102 :duration 2}
                 {:kind :epoch :event [:other-event] :start 103 :end 105 :duration 2}
                 {:kind :epoch :event [:page/scrolled 20] :start 106 :end 109 :duration 3}]
        groups (layout/group-records records)
        scroll (first groups)]
    (is (= 2 (count groups)))
    (is (= [:event :page/scrolled] (:id scroll)))
    (is (= 2 (:count scroll)))
    (is (= 5 (:duration scroll)))
    (is (= [20] (:latest-args scroll)))
    (is (= [[:page/scrolled 10] [:page/scrolled 20]] (mapv :event (:occurrences scroll))))))

(deftest event-completion-matches-ids-and-args-and-hides-only-sub-runs
  (is (= [:trace :event/handler :page/scrolled]
         (layout/record-key {:kind :trace :op-type :event/handler :operation [:page/scrolled 10]})))
  (is (= (layout/record-key {:kind :trace :op-type :event/handler :operation [:page/scrolled 10]})
         (layout/record-key {:kind :trace :op-type :event/handler :operation [:page/scrolled 20]})))
  (is (views/vector-matches? "[:page/scrolled]" [:page/scrolled 10]))
  (is (not (views/vector-matches? "[:page/scrolled 20]" [:page/scrolled 10])))
  (is (= [[:page/scrolled 10]] (views/vector-suggestions "[:page/scrolled 10]" [:page/scrolled] [[:page/scrolled 10]])))
  (let [records [{:kind :trace :op-type :sub/run}
                 {:kind :trace :op-type :sub/create}
                 {:kind :epoch :event [:page/scrolled 10]}]]
    (is (= 2 (count (views/timing-records records true))))
    (is (= records (views/timing-records records false)))
    (is (= [[:page/scrolled 10]] (views/captured-events records)))))
