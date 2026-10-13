(ns tolgraven.suspense-deferred-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [reagent.core :as r]
            [reagent.dom.client :as dom]
            [reagent.dom.server :as server]
            [reagent.impl.batching :as batching]
            [tolgraven.component.restore :as restore]
            [tolgraven.component.hydration :as hydration]
            [tolgraven.react :as rf]
            [tolgraven.test-support :as support]))

(r/defc <highlighted> [committed!]
  (rf/use-layout-effect (fn [] (committed!) nil) [])
  [:pre [:code [:span {:style {:color "#fb4934"}} "defn"] " greet"]])

(r/defc <formatter> [server? lazy-view]
  (let [[defer?] (rf/use-state #(and (not server?) (restore/initial-hydration?)))
        committed! (hydration/use-deferred! defer?)]
    [rf/suspense {:fallback (r/as-element [:pre [:code "plain fallback"]])}
     (if server? [<highlighted> (fn [])]
         (rf/create-element lazy-view #js {:committed committed!}))]))

(r/defc <boundary> [server? lazy-view committed!]
  (let [[wrap? set-wrap!] (rf/use-state false)]
    (rf/use-layout-effect (fn [] (committed!) nil) [])
    [:div {:class (when wrap? "wrapped")
           :data-hydrating (:hydrate? @restore/*context)}
     [:button {:type "button" :on-click #(set-wrap! (not wrap?))} "Wrap"]
     [<formatter> server? lazy-view]]))

(deftest suspense-preserves-server-code-before-and-after-deferred-hydration
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                *root (atom nil)
                *release (atom nil)
                *commit (atom nil)
                *formatter-commit (atom nil)
                before-restore @restore/*context
                *acquisitions (atom 0)
                *errors (atom [])
                gate (js/Promise. (fn [resolve _] (reset! *release resolve)))
                committed (js/Promise. (fn [resolve _] (reset! *commit resolve)))
                formatter-committed (js/Promise. (fn [resolve _] (reset! *formatter-commit resolve)))
                lazy-view (rf/lazy
                            (fn []
                              (-> gate
                                  (.then (fn [_]
                                           (swap! *acquisitions inc)
                                           #js {:default (rf/reactify-component
                                                           (fn [{:keys [committed]}]
                                                             [<highlighted>
                                                              (fn [] (committed) (@*formatter-commit nil))]))})))))]
            (try
              (restore/begin! {:hydrate? true})
              (set! (.-innerHTML element) (server/render-to-string [<boundary> true nil (fn [])]))
              (let [pre (.querySelector element "pre")
                    span (.querySelector element "span")]
                (reset! *root
                  (dom/hydrate-root element [<boundary> false lazy-view #(@*commit nil)]
                    {:on-recoverable-error #(swap! *errors conj (str %))}))
                (await! committed)
                (is (= 0 @*acquisitions) "The rest of the page commits without acquiring deferred code")
                (is (identical? pre (.querySelector element "pre")))
                (is (identical? span (.querySelector element "span")))
                (is (= "defn greet" (.-textContent pre)) "SSR remains visible, never the plain fallback")
                (.click (.querySelector element "button"))
                (await! (support/wait-for! #(= "wrapped" (.-className (.-firstElementChild element)))))
                (is (identical? span (.querySelector element "span")) "An outer control works before inner hydration")
                (restore/hydrated!)
                (await! (support/settle!))
                (@*release nil)
                (await! formatter-committed)
                (is (pos? @*acquisitions))
                (is (identical? pre (.querySelector element "pre")))
                (is (identical? span (.querySelector element "span")))
                (is (empty? @*errors) (pr-str @*errors)))
              (finally
                (when @*root (dom/unmount @*root))
                (reset! restore/*context before-restore)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(r/defc <hold-probe> [id *callbacks]
  (let [complete! (hydration/use-deferred! true)]
    (rf/use-layout-effect #(swap! *callbacks assoc id complete!) [id complete!])
    [:span (name id)]))

(r/defc <early-child> [complete!]
  (rf/use-layout-effect complete! [])
  [:span "Ready"])

(r/defc <cached-boundary> []
  (let [complete! (hydration/use-deferred! true)]
    [<early-child> complete!]))

(r/defc <discarded> [gate] (throw gate))

(deftest cached-inner-commit-does-not-install-a-stale-global-owner
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                original batching/react-flush]
            (try
              (await! (support/render! root [:> rf/strict-mode [<cached-boundary>]]))
              (is (= "Ready" (.-textContent element)))
              (is (identical? original batching/react-flush)
                  "Child completion before parent setup must leave no batching override")
              (finally (support/unmount! root) (hydration/release-all!)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest overlapping-and-abandoned-hydration-owners-release-without-stale-completion
  (async done
    (let [*callbacks (atom {})
          gate (js/Promise. (fn [_ _]))]
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))
                  original batching/react-flush]
              (try
                (await! (support/render! root
                          [rf/suspense {:fallback (r/as-element [:p "Waiting"])}
                           [:<> [<hold-probe> :discarded *callbacks] [<discarded> gate]]]))
                (is (identical? original batching/react-flush) "Uncommitted renders acquire no owner")
                (is (empty? @*callbacks))
                (await! (support/render! root
                          [:> rf/strict-mode
                           [:<> (with-meta [<hold-probe> :first *callbacks] {:key :first})
                            (with-meta [<hold-probe> :second *callbacks] {:key :second})]]))
                (is (identical? rf/start-transition batching/react-flush))
                ((:first @*callbacks))
                (is (identical? rf/start-transition batching/react-flush)
                    "The remaining owner keeps transition batching")
                ((:second @*callbacks))
                (is (identical? original batching/react-flush))
                (await! (support/render! root
                          (with-meta [<hold-probe> :old *callbacks] {:key :old})))
                (let [old! (:old @*callbacks)]
                  (hydration/release-all!)
                  (is (identical? original batching/react-flush))
                  (await! (support/render! root
                            (with-meta [<hold-probe> :new *callbacks] {:key :new})))
                  (old!)
                  (is (identical? rf/start-transition batching/react-flush)
                      "An old document completion cannot release a new owner's gate"))
                (await! (support/render! root [:p "Away"]))
                (is (identical? original batching/react-flush) "Unmount releases the pending owner")
                (finally (support/unmount! root) (hydration/release-all!)))))
          (.catch #(is false (str %)))
          (.finally done)))))
