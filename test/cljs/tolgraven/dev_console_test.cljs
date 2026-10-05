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
