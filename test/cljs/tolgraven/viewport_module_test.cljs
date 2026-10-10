(ns tolgraven.viewport-module-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [reagent.dom.client :as dom]
            [reagent.dom.server :as server]
            [shadow.lazy :as lazy]
            [tolgraven.react :as rf]
            [tolgraven.macros :as m]
            [tolgraven.loader :as loader]
            [tolgraven.loader.activation :as activation]
            [tolgraven.loader.styles :as styles]
            [tolgraven.component.restore :as restore]
            [tolgraven.dev-console.capture :as capture]
            [tolgraven.render-context :as context]
            [tolgraven.test-support :as support]
            [tolgraven.events]
            [tolgraven.subs]))

(m/defc <target>
  {:features [[:appear "opacity"]]}
  [title committed!]
  (rf/use-layout-effect (fn [] (committed!) js/undefined) #js [])
  [:article {:data-viewport-target true} [:strong title]])

(m/defc <outer> [module committed! outer-committed!]
  (let [value @(rf/subscribe [:state [:viewport-fixture]])]
    (rf/use-layout-effect (fn [] (outer-committed!) js/undefined) #js [])
    [:div
     [:button {:on-click #(rf/dispatch [:state [:viewport-fixture] "Changed"])} (or value "Original")]
     (m/<> {:module module} "Server section" committed!)]))

(defn observer-fixture! []
  (let [previous js/IntersectionObserver
        *observers (atom [])]
    (set! js/IntersectionObserver
      (fn [callback options]
        (let [observer (js-obj)
              *element (atom nil)
              *stopped? (atom false)]
          (aset observer "observe" #(reset! *element %))
          (aset observer "disconnect" #(reset! *stopped? true))
          (swap! *observers conj {:element *element
                                 :stopped? *stopped?
                                 :options options
                                 :fire! (fn [visible?]
                                          (callback #js [#js {:isIntersecting visible?
                                                             :intersectionRatio (if visible? 1 0)}]
                                                    observer))})
          observer)))
    {:observers *observers
     :restore! #(set! js/IntersectionObserver previous)}))

(defn transport-fixture! [module spec]
  (let [original-modules loader/modules
        original-css styles/acquire!
        original-load lazy/load
        *ready? (atom false)
        *started (atom [])
        *css (atom nil)
        *code (atom nil)
        css (js/Promise. (fn [resolve _] (reset! *css resolve)))
        code (js/Promise. (fn [resolve _] (reset! *code resolve)))
        loadable (reify lazy/ILoadable (ready? [_] @*ready?) IDeref (-deref [_] spec))]
    (set! loader/modules (assoc original-modules module loadable))
    (set! styles/acquire!
      (fn [id]
        (if (= id module)
          (do (swap! *started conj :css) css)
          (original-css id))))
    (set! lazy/load (fn
                     ([_] (swap! *started conj :js)
                          (.then code (fn [value] (reset! *ready? true) value)))
                     ([_ _] code)))
    {:started *started
     :code! #(@*code spec)
     :css! #(@*css nil)
     :restore! (fn []
                 (set! loader/modules original-modules)
                 (set! styles/acquire! original-css)
                 (set! lazy/load original-load)
                 (swap! loader/*code-loads dissoc module)
                 (swap! loader/*loads dissoc module)
                 (swap! loader/*installed disj module)
                 (doseq [path [[:loader :code-ready module] [:loader :requested module]
                               [:loader :errors module] [:state :init :scope module]]]
                   (rf/dispatch [:component-data/remove path])))}))

(deftest view-activation-shares-assets-and-init-with-an-earlier-trigger
  (async done
    (-> (go-promise
          (let [module (keyword (str "viewport-" (random-uuid)))
                *init (atom 0)
                element (.createElement js/document "div")
                root (await! (support/create-root! element))
                observers (observer-fixture!)
                transport (transport-fixture! module {:id module
                                                     :view {:view <target>}
                                                     :init #(swap! *init inc)})]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root [:div
                                             [loader/<trigger> {:module module :lead-pages 3}]
                                             (m/<> {:module module} "One" (fn []))
                                             (m/<> {:module module} "Two" (fn []))]))
              (is (empty? @(:started transport)) "Mounting offscreen modules acquires no CSS or JS")
              (is (zero? @*init))
              (let [observer (first @(:observers observers))]
                (is (.endsWith (.-rootMargin (:options observer)) "% 0%"))
                ((:fire! observer) false)
                (await! (support/settle!))
                (is (empty? @(:started transport)))
                ((:fire! observer) true))
              (await! (support/wait-for! #(seq @(:started transport)) "Module trigger did not start assets"))
              (is (= [:css :js] @(:started transport)) "The proximity trigger starts CSS directly before JS")
              ((:code! transport))
              (await! (support/settle!))
              (is (nil? (.querySelector element "[data-viewport-target]")) "JavaScript alone cannot mount an unstyled section")
              ((:css! transport))
              (await! (support/wait-for! #(= 2 (.-length (.querySelectorAll element "[data-viewport-target]")))))
              (await! (support/wait-for! #(= 1 @*init)))
              (is (= [:css :js] @(:started transport)))
              (is (nil? (.querySelector element ".module-load-placeholder")))
              (support/unmount! root)
              (is (every? #(deref (:stopped? %)) @(:observers observers)))
              (finally
                (support/unmount! root) (.remove element)
                ((:restore! transport)) ((:restore! observers))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest manual-module-waits-for-re-frame-intent
  (async done
    (-> (go-promise
          (let [module (keyword (str "manual-" (random-uuid)))
                element (.createElement js/document "div")
                root (await! (support/create-root! element))
                observers (observer-fixture!)
                transport (transport-fixture! module {:id module :view {:view <target>}})]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root (m/<> {:module module :load-on :event} "Manual" (fn []))))
              (.dispatchEvent (.-firstElementChild element) (js/Event. "focusin"))
              (.click (.-firstElementChild element))
              (await! (support/settle!))
              (is (empty? @(:started transport)))
              (is (empty? @(:observers observers)))
              ;; Existing externally defined controls still use the normal event.
              (rf/dispatch [:scope/init module])
              (await! (support/wait-for! #(seq @(:started transport)) "SSR intent did not start assets"))
              ((:css! transport)) ((:code! transport))
              (await! (support/wait-for! #(.querySelector element "[data-viewport-target]")))
              (is (= [:css :js] @(:started transport)))
              (finally
                (support/unmount! root) (.remove element)
                ((:restore! transport)) ((:restore! observers))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest deferred-module-hydration-preserves-ssr-through-ancestor-events
  (async done
    (-> (go-promise
          (let [module (keyword (str "ssr-viewport-" (random-uuid)))
                element (.createElement js/document "div")
                *root (atom nil)
                *outer (atom nil)
                *inner (atom nil)
                outer (js/Promise. (fn [resolve _] (reset! *outer resolve)))
                inner (js/Promise. (fn [resolve _] (reset! *inner resolve)))
                spec {:id module :view {:view <target>}}
                before @restore/*context
                previous (await! (support/state-at! [:state :viewport-fixture]))
                observers (observer-fixture!)
                transport (transport-fixture! module spec)
                stop-capture! (capture/connect!)
                stop-record! (capture/start!)]
            (.appendChild (.-body js/document) element)
            (try
              (rf/dispatch [:state [:viewport-fixture] nil])
              (await! (support/settle!))
              (restore/begin! {:hydrate? true})
              (restore/install-module-views! {module [:view]})
              (set! (.-innerHTML element)
                    (binding [context/*server?* true context/*modules* {module spec}]
                      (server/render-to-string [<outer> module (fn []) (fn [])])))
              (let [article (.querySelector element "article")
                    strong (.querySelector element "strong")]
                (reset! *root (dom/hydrate-root element [<outer> module #(@*inner nil) #(@*outer nil)]))
                (await! outer)
                (is (empty? @(:started transport)))
                (is (identical? article (.querySelector element "article")))
                (.click (.querySelector element "button"))
                (await! (support/wait-for! #(= "Changed" (.-textContent (.querySelector element "button"))) "SSR button event did not render"))
                (is (identical? strong (.querySelector element "strong")) "An outer re-frame event preserves unhydrated SSR")
                (restore/hydrated!)
                (await! (support/settle!))
                (.click strong)
                (await! (support/wait-for! #(seq @(:started transport)) "SSR intent did not start assets"))
                ((:code! transport)) ((:css! transport))
                (await! (js/Promise.race #js [inner
                                             (js/Promise. (fn [_ reject]
                                                            (js/setTimeout #(reject (js/Error. "Inner module did not hydrate")) 3000)))]))
                (await! (support/wait-for! #(nil? (.querySelector element "template")) "SSR template did not disappear"))
                (is (identical? article (.querySelector element "article")))
                (is (identical? strong (.querySelector element "strong")))
                (is (= "Server section" (.-textContent article)))
                (is (.contains (.-classList article) "appeared")
                    "Late hydration retains the visible SSR appearance"))
              (finally
                (when @*root (dom/unmount @*root)) (.remove element)
                (rf/dispatch [:state [:viewport-fixture] previous])
                (reset! restore/*context before)
                (stop-record!) (stop-capture!)
                ((:restore! transport)) ((:restore! observers))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest intent-adapter-cleans-up-and-falls-back-without-intersection-observer
  (async done
    (-> (go-promise
          (let [module (keyword (str "intent-" (random-uuid)))
                element (.createElement js/document "section")
                observers (observer-fixture!)
                transport (transport-fixture! module {:id module})]
            (.appendChild (.-body js/document) element)
            (try
              (let [stop! (activation/setup! element {:module module :load-on :hover})]
                (.dispatchEvent element (js/Event. "click"))
                (await! (support/settle!))
                (is (empty? @(:started transport)))
                (stop!)
                (.dispatchEvent element (js/Event. "pointerenter"))
                (await! (support/settle!))
                (is (empty? @(:started transport)) "Removed intent listeners cannot acquire a module"))
              (set! js/IntersectionObserver js/undefined)
              (let [stop! (activation/setup! element {:module module})]
                (await! (support/wait-for! #(seq @(:started transport)) "Observer fallback did not start assets"))
                (is (= [:css :js] @(:started transport)) "Browsers without observers still acquire usable content")
                ((:code! transport)) ((:css! transport))
                (await! (loader/acquire-code! module))
                (stop!))
              (finally (.remove element) ((:restore! transport)) ((:restore! observers))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest failed-assets-retry-silently-once-before-showing-the-local-error
  (async done
    (-> (go-promise
          (let [module (keyword (str "retry-" (random-uuid)))
                element (.createElement js/document "div")
                root (await! (support/create-root! element))
                original-modules loader/modules
                original-css styles/acquire!
                original-delay loader/retry-delay-ms
                *attempts (atom 0)
                *times (atom [])
                spec {:id module :view {:view <target>}}
                loadable (reify lazy/ILoadable (ready? [_] true) IDeref (-deref [_] spec))]
            (.appendChild (.-body js/document) element)
            (try
              (set! loader/modules (assoc original-modules module loadable))
              (set! loader/retry-delay-ms 90)
              (set! styles/acquire!
                (fn [_]
                  (swap! *times conj (.now js/performance))
                  (if (<= (swap! *attempts inc) 2)
                    (js/Promise.reject (js/Error. "Transient asset failure"))
                    (js/Promise.resolve nil))))
              (await! (support/render! root (m/<> {:module module :load-on :immediate} "Retried" (fn []))))
              (await! (support/wait-for! #(pos? @*attempts)))
              (is (= 1 @*attempts))
              (is (nil? (.querySelector element "[role=alert]")) "The first failure remains silent")
              (await! (support/wait-for! #(.querySelector element "[role=alert]")))
              (is (= 2 @*attempts) "Only a failed automatic retry reveals the error")
              (is (>= (- (second @*times) (first @*times)) 80) "Retry is delayed, not an immediate second request")
              (.click (.querySelector element "[role=alert] button"))
              (await! (support/wait-for! #(.querySelector element "[data-viewport-target]")))
              (is (= 3 @*attempts))
              (is (nil? (.querySelector element "[role=alert]")))
              (finally
                (support/unmount! root) (.remove element)
                (set! loader/modules original-modules)
                (set! styles/acquire! original-css)
                (set! loader/retry-delay-ms original-delay)
                (swap! loader/*code-loads dissoc module)
                (swap! loader/*loads dissoc module)
                (swap! loader/*installed disj module)
                (doseq [path [[:loader :code-ready module] [:loader :requested module]
                              [:loader :errors module] [:state :init :scope module]]]
                  (rf/dispatch [:component-data/remove path]))))))
        (.catch #(is false (str %)))
        (.finally done))))
