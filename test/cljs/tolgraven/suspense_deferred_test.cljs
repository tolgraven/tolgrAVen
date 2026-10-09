(ns tolgraven.suspense-deferred-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [reagent.core :as r]
            [reagent.dom.client :as dom]
            [reagent.dom.server :as server]
            [tolgraven.component.restore :as restore]
            [tolgraven.component.hydration :as hydration]
            [tolgraven.react :as rf]
            [tolgraven.test-support :as support]))

(r/defc <highlighted> [committed!]
  (rf/use-layout-effect (fn [] (committed!) js/undefined) #js [])
  [:pre [:code [:span {:style {:color "#fb4934"}} "defn"] " greet"]])

(r/defc <formatter> [server? lazy-view]
  (let [[defer?] (rf/use-state #(and (not server?) (restore/initial-hydration?)))
        committed! (hydration/use-deferred! defer?)]
    [rf/suspense {:fallback (r/as-element [:pre [:code "plain fallback"]])}
     (if server? [<highlighted> (fn [])]
         (rf/create-element lazy-view #js {:committed committed!}))]))

(r/defc <boundary> [server? lazy-view committed!]
  (let [[wrap? set-wrap!] (rf/use-state false)]
    (rf/use-layout-effect (fn [] (committed!) js/undefined) #js [])
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
