(ns website.viewport-module-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]

            [shadow.lazy :as lazy]

            [website.macros :as m]
            [website.loader :as loader]
            [website.loader.activation :as activation]
            [website.loader.styles :as styles]
            [website.components.shell :as shell]
            [website.component.restore :as restore]
            [website.dev-console.capture :as capture]
            [website.render-context :as context]
            [website.test-support :as support]
            [website.events]
            [website.subs]))

(m/defc <target>
  {:features [[:appear "opacity"]]}
  [title committed!]
  (r/use-layout-effect (fn [] (committed!) nil) [])
  [:article {:data-viewport-target true} [:strong title]])

(m/defc <outer> [module committed! outer-committed!]
  (let [value @(r/subscribe [:state [:viewport-fixture]])]
    (r/use-layout-effect (fn [] (outer-committed!) nil) [])
    [:div
     [:button {:on-click #(r/dispatch [:state [:viewport-fixture] "Changed"])} (or value "Original")]
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
                   (r/dispatch [:component-data/remove path])))}))

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
              (is (every? #(.matches @(:element %) ".component-skeleton") @(:observers observers))
                  "Only the loading fallback observes visibility; the module stays manual")
              ;; Existing externally defined controls still use the normal event.
              (r/dispatch [:scope/init module])
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
            ;; The full suite's result document can be much taller than a
            ;; viewport. This case explicitly covers visible SSR, not a later
            ;; offscreen entrance waiting on the controlled observer fixture.
            (set! (.-cssText (.-style element))
                  "position:fixed;top:1rem;left:1rem;width:20rem;z-index:99999")
            (.appendChild (.-body js/document) element)
            (try
              (r/dispatch [:state [:viewport-fixture] nil])
              (await! (support/settle!))
              (restore/begin! {:hydrate? true})
              (restore/install-module-views! {module [:view]})
              (set! (.-innerHTML element)
                    (binding [context/*server?* true context/*modules* {module spec}]
                      (r/render-to-string [<outer> module (fn []) (fn [])])))
              (let [article (.querySelector element "article")
                    strong (.querySelector element "strong")]
                (reset! *root (r/hydrate-root element [<outer> module #(@*inner nil) #(@*outer nil)]))
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
                (let [bounds (.getBoundingClientRect article)]
                  (is (and (pos? (.-bottom bounds)) (pos? (.-right bounds))
                           (< (.-top bounds) (.-innerHeight js/window))
                           (< (.-left bounds) (.-innerWidth js/window)))
                      "The native SSR section is actually inside the viewport"))
                (is (.contains (.-classList article) "appeared")
                    "Late hydration retains the visible SSR appearance"))
              (finally
                (when @*root (r/unmount @*root)) (.remove element)
                (r/dispatch [:state [:viewport-fixture] previous])
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
                  (r/dispatch [:component-data/remove path]))))))
        (.catch #(is false (str %)))
        (.finally done))))

(defn <reserved-avatar> [& _]
  [:div.user-avatar-container {:data-custom-avatar true}
   [:span.user-avatar {:aria-hidden true, :style {:visibility "hidden"}}]])

(defn <fragment-loading> [& _]
  [:<>
   [:span {:data-first-loading-child true} "Pending section"]
   [:button {:type "button", :data-loading-intent true} "Open pending section"]])

(defn- comment-footprint [element]
  (let [text (.querySelector element "[data-budget-text]")
        card (.querySelector element ".blog-comment")
        avatar (.querySelector element "[data-custom-avatar]")]
    {:text-top (.-offsetTop text)
     :card-height (.-offsetHeight card)
     :avatar-height (.-offsetHeight avatar)
     :avatar-float (.-cssFloat (js/getComputedStyle avatar))}))

(defn- drain-transport! [module transport]
  ((:css! transport))
  ((:code! transport))
  (if (seq @(:started transport))
    (loader/load! {:module module})
    (js/Promise.resolve nil)))

(deftest custom-functional-loading-retains-its-native-comment-footprint
  (async done
    (-> (go-promise
          (let [module (keyword (str "custom-footprint-" (random-uuid)))
                element (.createElement js/document "div")
                root (await! (support/create-root! element))
                observers (observer-fixture!)
                transport (transport-fixture! module {:id module :view {:view <reserved-avatar>}})]
            (set! (.-cssText (.-style element))
                  "position:fixed;top:1rem;left:1rem;width:24rem;z-index:99999")
            (.appendChild (.-body js/document) element)
            (try
              (await! (styles/acquire-path! "/css/website/main.css"))
              (await! (styles/acquire! :blog))
              (await! (support/render! root
                        [:div.blog
                         [:section.blog-comment
                          (m/<> {:module module :<loading> <reserved-avatar>})
                          [:div.blog-comment-main
                           [:h3.blog-comment-title "Native comment budget"]
                           [:div {:data-budget-text true} "Text below the title"]]]]))
              (let [pending (comment-footprint element)
                    avatar (.querySelector element "[data-custom-avatar]")
                    observer (some #(when (identical? avatar @(:element %)) %) @(:observers observers))]
                (is (some? observer) "Visibility observes the functional fallback's actual native child")
                (is (empty? @(:started transport)) "A pending custom root waits for normal proximity")
                (when observer ((:fire! observer) true))
                (await! (support/wait-for! #(seq @(:started transport))))
                (await! (drain-transport! module transport))
                (await! (support/wait-for! #(nil? (.querySelector element ".module-load-placeholder"))))
                (let [loaded (comment-footprint element)]
                  (is (pos? (:avatar-height pending)) "The native pending avatar reserves real height")
                  (is (= "left" (:avatar-float pending)))
                  (is (= pending loaded)
                      "Code arrival adds no section padding, border or text-budget jump")))
              (support/unmount! root)
              (is (every? #(deref (:stopped? %)) @(:observers observers)))
              (finally
                (support/unmount! root)
                (try
                  (await! (drain-transport! module transport))
                  (finally
                    (.remove element)
                    ((:restore! transport))
                    ((:restore! observers))))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest fragment-loading-observes-native-roots-and-retains-sibling-intent
  (async done
    (-> (go-promise
          (doseq [intent [:click :focus :view]]
            (let [module (keyword (str "fragment-" (name intent) "-" (random-uuid)))
                  element (.createElement js/document "div")
                  root (await! (support/create-root! element))
                  observers (observer-fixture!)
                  transport (transport-fixture! module {:id module :view {:view <target>}})]
              (.appendChild (.-body js/document) element)
              (try
                (await! (support/render! root
                          [:div
                           [:button {:type "button", :data-outside-intent true} "Unrelated control"]
                           (m/<> {:module module :<loading> <fragment-loading>}
                                 "Loaded fragment" (fn []))]))
                (let [first-child (.querySelector element "[data-first-loading-child]")
                      sibling (.querySelector element "[data-loading-intent]")
                      installed @(:observers observers)
                      observed (mapv #(deref (:element %)) installed)
                      sibling-observer (some #(when (identical? sibling @(:element %)) %) installed)]
                  (is (some #(identical? first-child %) observed)
                      "Proximity uses the fragment's first actual native element")
                  (is (some? sibling-observer)
                      "A later visible root retains proximity activation")
                  (.click (.querySelector element "[data-outside-intent]"))
                  (await! (support/settle!))
                  (is (empty? @(:started transport)) "Intent outside the module owner cannot activate it")
                  (case intent
                    :click (.click sibling)
                    :focus (.focus sibling #js {:preventScroll true})
                    :view (when sibling-observer ((:fire! sibling-observer) true)))
                  (await! (support/wait-for! #(seq @(:started transport))
                            (str "A later fallback sibling lost its " intent " activation")))
                  ;; Multiple roots reporting visibility share one acquisition.
                  (doseq [observer installed] ((:fire! observer) true))
                  (await! (support/settle!))
                  (is (= [:css :js] @(:started transport))))
                (await! (drain-transport! module transport))
                (await! (support/wait-for! #(.querySelector element "[data-viewport-target]")))
                (is (= "Loaded fragment" (.-textContent (.querySelector element "[data-viewport-target]"))))
                (finally
                  (support/unmount! root)
                  (try
                    (await! (drain-transport! module transport))
                    (finally
                      (.remove element)
                      ((:restore! transport))
                      ((:restore! observers)))))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest custom-loading-unmount-retires-proximity-and-sibling-intent
  (async done
    (-> (go-promise
          (let [module (keyword (str "custom-cleanup-" (random-uuid)))
                element (.createElement js/document "div")
                root (await! (support/create-root! element))
                observers (observer-fixture!)
                transport (transport-fixture! module {:id module :view {:view <target>}})]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root
                        (m/<> {:module module :<loading> <fragment-loading>} "Unused" (fn []))))
              (let [owner (.querySelector element ".module-load-placeholder")
                    sibling (.querySelector element "[data-loading-intent]")
                    installed (vec @(:observers observers))]
                (is (seq installed) "The pending module installed proximity observation")
                (support/unmount! root)
                ;; Retain only test-owned detached markup in the document so a
                ;; leaked document-capture intent listener would still see it.
                (.appendChild element owner)
                (.click sibling)
                (.dispatchEvent sibling (js/Event. "focusin" #js {:bubbles true}))
                (doseq [observer installed] ((:fire! observer) true))
                (.dispatchEvent js/window (js/Event. "resize"))
                (await! (support/settle!))
                (is (empty? @(:started transport)) "Retired native intent and stale visibility cannot acquire code")
                (is (= (count installed) (count @(:observers observers)))
                    "Unmount removes resize-driven observation")
                (is (every? #(deref (:stopped? %)) installed)))
              (finally
                (support/unmount! root)
                (try
                  (await! (drain-transport! module transport))
                  (finally
                    (.remove element)
                    ((:restore! transport))
                    ((:restore! observers))))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest empty-custom-loading-preserves-immediate-and-manual-acquisition
  (async done
    (-> (go-promise
          (doseq [mode [:event :immediate]]
            (let [module (keyword (str "empty-loading-" (name mode) "-" (random-uuid)))
                  element (.createElement js/document "div")
                  root (await! (support/create-root! element))
                  observers (observer-fixture!)
                  transport (transport-fixture! module {:id module :view {:view <target>}})]
              (.appendChild (.-body js/document) element)
              (try
                (await! (support/render! root
                          (m/<> {:module module :load-on mode :<loading> [:<>]}
                                "Empty fallback resolved" (fn []))))
                (is (empty? @(:observers observers)) "Empty manual/immediate roots need no geometry target")
                (when (= :event mode)
                  (is (empty? @(:started transport)) "An empty manual fallback remains intent-controlled")
                  (r/dispatch [:scope/init module]))
                (await! (support/wait-for! #(seq @(:started transport))
                          (str "Empty fallback did not acquire through " mode)))
                (is (= [:css :js] @(:started transport)))
                (await! (drain-transport! module transport))
                (await! (support/wait-for! #(.querySelector element "[data-viewport-target]")))
                (is (= "Empty fallback resolved"
                       (.-textContent (.querySelector element "[data-viewport-target]"))))
                (finally
                  (support/unmount! root)
                  (try
                    (await! (drain-transport! module transport))
                    (finally
                      (.remove element)
                      ((:restore! transport))
                      ((:restore! observers)))))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest default-loading-retains-its-section-and-existing-proximity-owner
  (async done
    (-> (go-promise
          (let [module (keyword (str "default-loading-" (random-uuid)))
                element (.createElement js/document "div")
                root (await! (support/create-root! element))
                observers (observer-fixture!)
                transport (transport-fixture! module {:id module :view {:view <target>}})]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root (m/<> {:module module} "Default fallback" (fn []))))
              (let [owner (.querySelector element ".module-load-placeholder")
                    observer (some #(when (identical? owner @(:element %)) %) @(:observers observers))]
                (is (= "SECTION" (.-tagName owner)) "The generic pending section is preserved")
                (is (some? (.querySelector owner ".component-skeleton")))
                (is (some? observer) "Default proximity still observes the original pending owner")
                (is (empty? @(:started transport)))
                (when observer ((:fire! observer) true)))
              (await! (support/wait-for! #(seq @(:started transport))))
              (await! (drain-transport! module transport))
              (await! (support/wait-for! #(.querySelector element "[data-viewport-target]")))
              (finally
                (support/unmount! root)
                (try
                  (await! (drain-transport! module transport))
                  (finally
                    (.remove element)
                    ((:restore! transport))
                    ((:restore! observers))))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest header-search-retains-first-click-and-control-through-code-arrival
  (async done
    (-> (go-promise
          (let [module :search
                element (.createElement js/document "div")
                root (await! (support/create-root! element))
                original-state (await! (support/state-at! [:state :search]))
                original-loader (await! (support/state-at! [:loader]))
                original-init (await! (support/state-at! [:state :init :scope module]))
                original-loads @loader/*loads
                original-code @loader/*code-loads
                original-installed @loader/*installed
                observers (observer-fixture!)
                transport (transport-fixture! module {:id module :view {:view <target>}})]
            (.appendChild (.-body js/document) element)
            (try
              (swap! loader/*loads dissoc module)
              (swap! loader/*code-loads dissoc module)
              (r/dispatch [:component-data/remove [:loader :requested module]])
              (r/dispatch [:component-data/remove [:loader :code-ready module]])
              (r/dispatch [:state [:search :open?] false])
              (await! (support/render! root [shell/<header> {:menu {}}]))
              (let [button (.querySelector element "button.search-ui-btn")]
                (.focus button)
                (await! (support/settle!))
                (is (empty? @(:started transport)) "Focus keeps Search cold and the native click target mounted")
                (is (identical? button (.querySelector element "button.search-ui-btn")))
                (.click button)
                (await! (support/wait-for! #(= "true" (.getAttribute button "aria-busy"))))
                (is (= "true" (.getAttribute button "aria-expanded")))
                (is (identical? button (.querySelector element "button.search-ui-btn")))
                (is (nil? (.querySelector element ".module-load-placeholder")))
                (.click button)
                (await! (support/wait-for! #(= "false" (.getAttribute button "aria-expanded"))))
                (is (= "false" (.getAttribute button "aria-busy")) "Closing cancels pending UI intent")
                (.click button)
                (await! (support/wait-for! #(= "true" (.getAttribute button "aria-busy"))))
                ((:code! transport))
                (await! (support/settle!))
                (is (= "true" (.getAttribute button "aria-busy")) "Code alone cannot release the CSS gate")
                ((:css! transport))
                (await! (support/wait-for! #(= "false" (.getAttribute button "aria-busy"))))
                (is (identical? button (.querySelector element "button.search-ui-btn")))
                (is (= "true" (.getAttribute button "aria-expanded")) "The first click remains open after acquisition"))
              (finally
                (support/unmount! root)
                (await! (drain-transport! module transport))
                ((:restore! transport))
                ((:restore! observers))
                (reset! loader/*loads original-loads)
                (reset! loader/*code-loads original-code)
                (reset! loader/*installed original-installed)
                (doseq [[path value] [[[:loader] original-loader]
                                      [[:state :search] original-state]
                                      [[:state :init :scope module] original-init]]]
                  (r/dispatch (if (nil? value)
                                [:component-data/remove path]
                                [:component-data/install path value])))
                (.remove element)))))
        (.catch #(is false (str %)))
        (.finally done))))
