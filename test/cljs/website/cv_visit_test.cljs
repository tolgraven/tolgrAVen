(ns website.cv-visit-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.component.legacy-storage :as legacy]
            [website.component.storage :as storage]
            [website.editor-visual :as fixture]
            [website.modules.cv.views :as cv]
            [website.modules.cv.events]
            [website.modules.coercion.module :as coercion]
            [website.validation.runtime :as validation-runtime]
            [website.react :as r]
            [website.test-support :as support]))

(defn visit-check! [saved snapshot?]
  (go-promise
    (let [before (r/make-restore-fn)
          element (.createElement js/document "div")
          root (await! (support/create-root! element))
          seed (await! (-> (js/fetch "/fixtures/editor-seed.json")
                           (.then #(.json %))
                           (.then #(js->clj % :keywordize-keys true))))
          timers (atom [])
          moves (atom [])
          reads (atom [])
          disk (atom (legacy/encode saved))
          resolve! (atom nil)
          ready (js/Promise. #(reset! resolve! %))]
      (r/with-framework-bindings
        [legacy/*state (atom nil)
         legacy/*loaded? (atom false)
         legacy/*pending (atom nil)
         storage/read-disk! (fn [key] (when (= key legacy/disk-key) @disk))
         storage/write-disk! (fn [key text] (when (= key legacy/disk-key) (reset! disk text)))
         storage/ready! (fn ([] ready) ([_] ready))
         storage/read! (fn [id options]
                         (swap! reads conj [id options])
                         {:value snapshot?})]
        (try
          (r/reg-fx :dispatch-later #(swap! timers into (if (vector? %) % [%])))
          (r/reg-fx :scroll/by #(swap! moves conj [:down %]))
          (r/reg-fx :scroll/px #(swap! moves conj [:back %]))
          (r/reg-fx :cv/focus #(swap! moves conj [:focus %]))
          (r/dispatch-sync [:editor-visual/install seed false])
          (await! (support/render! root [cv/<cv>]))
          (when-not (:cv-visited saved)
            (is (not (true? (await! (support/state-at! [:state :cv :visited]))))
                "Do not decide before asynchronous visit restoration completes"))
          (@resolve! nil)
          (await! (support/wait-for! #(true? (:cv-visited (legacy/read!)))))
          (is (true? (await! (support/state-at! [:state :cv :visited]))))
          (legacy/drain!)
          (is (true? (:cv-visited (legacy/decode @disk))))
          (is (= (:unrelated saved) (:unrelated (legacy/decode @disk))))
          (let [demo (filterv #(= :cv/demo (first (:dispatch %))) @timers)
                expected (if (or snapshot? (:cv-visited saved)) 0 3)]
            (is (= expected (count demo)))
            (when (pos? expected)
              (let [origin (.-scrollY js/window)]
                (doseq [timer demo]
                  (r/dispatch-sync (:dispatch timer))
                  (await! (support/settle!)))
                (is (= [[:down [50 nil]] [:back origin] [:focus true]] @moves))))
            (reset! moves [])
            (await! (support/render! root [:div "Away"]))
            (doseq [timer demo] (r/dispatch-sync (:dispatch timer)))
            (await! (support/settle!))
            (is (empty? @moves) "A departed CV cannot scroll or focus the next page")
            (reset! timers [])
            (await! (support/render! root [cv/<cv>]))
            (await! (support/settle!))
            (is (not-any? #(= :cv/demo (first (:dispatch %))) @timers) "SPA return never replays")
            (doseq [timer demo] (r/dispatch-sync (:dispatch timer)))
            (await! (support/settle!))
            (is (empty? @moves) "Old timers cannot animate a newly mounted CV")
            (await! (support/render! root [:div "Reload"]))
            (legacy/drain!)
            (reset! legacy/*state nil)
            (reset! legacy/*loaded? false)
            (r/dispatch-sync [:editor-visual/install seed false])
            (reset! timers [])
            (await! (support/render! root [cv/<cv>]))
            (await! (support/settle!))
            (is (not-any? #(= :cv/demo (first (:dispatch %))) @timers) "Reload reads the durable visit marker")
            (when snapshot?
              (is (= [[[:state [:state :cv :visited]] {:scope :public, :consume? false}]] @reads))))
          (finally
            (support/unmount! root)
            (await! (support/settle!))
            (legacy/drain!)
            (before)))))))

(deftest first-visit-is-claimed-once-across-remount-and-reload
  (async done
    (-> (visit-check! {:unrelated "preserved"} false)
        (.catch #(is false (str %)))
        (.finally done))))

(deftest existing-legacy-visit-skips-demo
  (async done
    (-> (visit-check! {:cv-visited true, :unrelated "preserved"} false)
        (.catch #(is false (str %)))
        (.finally done))))

(deftest existing-migrated-visit-waits-for-storage-and-skips-demo
  (async done
    (-> (visit-check! {:unrelated "preserved"} true)
        (.catch #(is false (str %)))
        (.finally done))))

;; Manual native-fixture checks use real disk persistence and the same deferred
;; engine installation as boot, rather than the unit tests' controlled boundary.
(defn ^:export enable-storage! []
  ((:install coercion/spec))
  (validation-runtime/install!)
  (storage/ready! {:scope :public}))
