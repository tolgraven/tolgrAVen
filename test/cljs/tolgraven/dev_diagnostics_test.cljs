(ns tolgraven.dev-diagnostics-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as rf]
            [re-frame.core :as re-frame]
            [tolgraven.dev.values :as values]
            [tolgraven.dev.stack :as stack]
            [tolgraven.dev.source-links :as source]
            [tolgraven.dev.consumer :as consumer]
            [tolgraven.dev-console.capture :as capture]
            [tolgraven.dev-console.layout :as layout]
            [tolgraven.dev-console.components :as components]
            [tolgraven.validation :as validation]
            [reagent.dom.server :as server]
            [reagent.core :as r]
            [reagent.ratom :as ratom]
            [tolgraven.test-support :as support]))

(rf/reg-sub :diagnostic-test/value (fn [db _] (get-in db [:test :diagnostic-value])))
(defc <consumer> [label]
  [:section [label] [:span (str @(rf/subscribe [:diagnostic-test/value]))]])

(deftest values-are-bounded-and-private-keys-are-redacted
  (is (= {:password :debug/redacted :nested {:access_token :debug/redacted :name "A"}}
         (values/safe-value {:password "private" :nested {:access_token "private" :name "A"}})))
  (is (= 20 (count (values/safe-value (range)))))
  (let [issues (validation/explain [:map [:age :int] [:password :int]] {:age "young" :password "private"})]
    (is (= #{:path :message} (set (keys (first issues)))))
    (is (= "string" (:actual-type (first (:dev/issues (meta issues))))))
    (is (= "young" (:actual (first (:dev/issues (meta issues))))))
    (is (= :debug/redacted (:actual (second (:dev/issues (meta issues)))))))
  (try
    (validation/check! "age" :int "young")
    (is false "Expected validation failure")
    (catch :default error (is (= "young" (:actual (first (:dev/issues (meta (:issues (ex-data error)))))))))))

(deftest mapped-lines-never-use-generated-fallback-positions
  (let [frame (stack/frame "    at example (http://localhost:4014/js/compiled/out/cljs-runtime/example.js:3:12)")
        decoded {2 {0 [{:source "src/frontend/example.cljs" :line 4 :col 1}]
                    10 [{:source "src/frontend/example.cljs" :line 8 :col 2}]}}]
    (is (= 3 (:line frame)))
    (is (= {:file "src/frontend/example.cljs" :line 9 :column 3} (stack/original-position decoded frame)))
    (is (nil? (stack/original-position decoded {:line 1 :column 1})))
    (is (nil? (stack/map-url "https://external.test/a.js" "http://localhost:4014")))
    (is (nil? (stack/map-url "/api/private.js" "http://localhost:4014")))
    (is (= "/js/compiled/out/cljs-runtime/example.js.map" (stack/map-url (:file frame) "http://localhost:4014")))
    (is (= "/docs/source/src/frontend/example.cljs#L9" (source/source-url "src/frontend/example.cljs" 9)))))

(deftest metrics-keep-full-query-scope-and-separate-body-from-inclusive-work
  (let [records [{:kind :trace :op-type :sub/run :tags {:query-v [:post 1]} :duration 2}
                 {:kind :trace :op-type :sub/run :tags {:query-v [:post 1]} :duration 4}
                 {:kind :trace :op-type :sub/run :tags {:query-v [:post 2]} :duration 1}
                 {:kind :render :component ["page" "view"] :phase "mount" :duration 20}
                 {:kind :view :component ["page" "view"] :duration 3}]
        groups (layout/metrics records "")
        post (some #(when (= [:subscription [:post 1]] (:id %)) %) groups)]
    (is (= 4 (count groups)))
    (is (= {:count 2 :total 6 :average 3 :maximum 4} (select-keys post [:count :total :average :maximum])))
    (is (= 1 (:mounts (first groups))))
    (is (= 2 (count (layout/metrics records "post"))))))

(deftest debug-source-queries-never-capture-themselves
  (is (capture/own? [:component-data/installed [:dev-console :source-catalog]]))
  (is (empty? (capture/trace-records [{:id 1 :op-type :sub/run
                                     :tags {:query-v [:component-data/installed [:dev-console :source-catalog]]}}]))))

(deftest component-queries-are-observed-without-changing-subscription-identity
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                stop! (capture/connect!)
                stop-record! (capture/start!)]
            (.appendChild (.-body js/document) element)
            (try
              (rf/dispatch [:component-data/install [:test :diagnostic-value] "before"])
              (await! (support/settle!))
              (await! (support/render! root [<consumer> :strong]))
              (capture/drain!)
              (await! (support/settle!))
              (let [debug (await! (support/mount-subscriptions! {:debug [:dev-console/data]}))]
                (try
                  (let [record (some #(when (= ["tolgraven.dev-diagnostics-test" "<consumer>"] (:component %)) %) (vals (:active (:debug ((:values debug))))))]
                    (is (some #{[:diagnostic-test/value]} (:queries record)))
                    (rf/dispatch [:component-data/install [:test :diagnostic-value] "after"])
                    (await! (support/settle!))
                    (capture/drain!)
                    (await! (support/settle!))
                    (is (re-find #"after" (.-textContent element)))
                    (let [records (:records (:debug ((:values debug))))]
                      (is (some #(and (= :view (:kind %))
                                      (some (fn [reason] (= :subscription-results (:cause reason))) (:reasons %))) records))))
                  (finally ((:unmount! debug)))))
              (finally (support/unmount! root) (.remove element) (stop-record!) (stop!)
                       (reset! capture/*recording? false)
                       (rf/dispatch [:component-data/remove [:test :diagnostic-value]])))))
        (.catch #(is false (str %))) (.finally done))))

(deftest structured-schema-values-retain-type-highlighting
  (let [html (server/render-to-string [values/<issues> [{:path [:age]
                                                        :actual "young"
                                                        :actual-type "string"
                                                        :required [:int {:min 18}]}]])]
    (is (re-find #"Received" html))
    (is (re-find #"dev-value--string" html))
    (is (re-find #"dev-value--keyword" html))
    (is (re-find #"dev-value--number" html))))

(deftest selected-unmounted-instances-retain-details-without-resurrection
  (async done
    (-> (go-promise
          (let [id (str (random-uuid))
                record {:kind :mount
                        :instance id
                        :component ["diagnostic-test" "temporary"]
                        :path [:component "diagnostic-test" "temporary"]
                        :queries [[:diagnostic-test/value]]}
                mounted (await! (support/mount-subscriptions! {:debug [:dev-console/data]}))]
            (try
              (rf/dispatch [:dev-console/records [record]])
              (rf/dispatch [:dev-console/pick id])
              (rf/dispatch [:dev-console/records [{:kind :unmount, :instance id}
                                                 {:kind :metadata, :instance id, :queries [[:late]]}]])
              (await! (support/settle!))
              (let [debug (:debug ((:values mounted)))]
                (is (nil? (get (:active debug) id)))
                (is (= [[:diagnostic-test/value]] (get-in debug [:selected-record :queries]))))
              (finally
                (rf/dispatch [:dev-console/pick nil])
                ((:unmount! mounted))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest observation-does-not-flush-or-revive-unread-reactions
  (let [owner (js-obj)
        *runs (atom 0)
        *flushes (atom 0)
        reaction (ratom/make-reaction #(do (swap! *runs inc) "computed"))
        enabled? @consumer/*enabled?]
    (try
      (reset! consumer/*enabled? true)
      (with-redefs [r/current-component (constantly owner)
                    ratom/flush! #(swap! *flushes inc)]
        (is (identical? reaction (consumer/subscribe! [:diagnostic-test/unread] reaction)))
        (consumer/render! {:ns "diagnostic-test", :name "unread"} [] (constantly [:div]))
        (is (zero? @*runs) "Observing an acquisition never runs its computation")
        (is (zero? @*flushes) "Observation never flushes other components"))
      (finally
        (reset! consumer/*enabled? enabled?)
        (ratom/dispose! reaction)))))

(deftest fragment-components-select-nearest-native-descendants
  (let [active {"logical" {:instance "logical", :native? false}
                "frame" {:instance "frame", :parent "logical", :native? true}
                "button" {:instance "button", :parent "frame", :native? true}
                "cycle" {:instance "cycle", :parent "cycle", :native? false}}]
    (is (= ["frame"] (components/native-roots active "logical")))
    (is (= ["frame"] (components/native-roots active "frame")))
    (is (empty? (components/native-roots active "cycle")))
    (is (empty? (components/native-roots active "missing")))))

(deftest early-capture-buffers-until-the-console-owner-commits
  (let [*events (atom [])
        record {:kind :view, :component ["diagnostic-test" "early"], :duration 1}]
    (with-redefs [capture/*pending (atom [])
                  capture/*tick (atom nil)
                  capture/*publishing? (atom false)
                  capture/*connected? (r/atom true)
                  capture/*instances (atom {})
                  consumer/*enabled? (atom true)
                  re-frame/dispatch #(swap! *events conj %)]
      (capture/emit! record)
      (capture/drain!)
      (is (empty? @*events) "Startup observations do not write into an uncommitted inspector")
      (let [stop! (capture/connect!)]
        (try
          (is (= [[:dev-console/records [record]]] @*events)
              "The committing owner publishes the original early observations")
          (finally (stop!)))))))

(r/defc <host-consumer> [*renders]
  (swap! *renders inc)
  [:pre (pr-str (capture/use-host-state))])

(deftest console-host-owns-queries-after-commit-and-releases-them
  (async done
    (-> (go-promise
          (let [restore-db! (re-frame/make-restore-fn)
                element (.createElement js/document "div")
                root (await! (support/create-root! element))
                *renders (atom 0)]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root [<host-consumer> *renders]))
              (rf/dispatch [:dev-console/option :recording? false])
              (rf/dispatch [:dev-console/records [{:kind :view :duration 7}]])
              (await! (support/settle!))
              (is (re-find #":recording\? false" (.-textContent element)))
              (is (re-find #":duration 7" (.-textContent element))
                  "Committed host observes the ordinary re-frame record events")
              (support/unmount! root)
              (await! (support/settle!))
              (let [renders @*renders]
                (rf/dispatch [:dev-console/option :recording? true])
                (await! (support/settle!))
                (is (= renders @*renders) "Unmounted host no longer observes its queries"))
              (finally
                (.remove element)
                (restore-db!)))))
        (.catch #(is false (str %)))
        (.finally done))))
