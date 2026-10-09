(ns tolgraven.components.code-block
  "Controls hydrate immediately; the formatter hydrates after the page is ready."
  (:require [tolgraven.component.registry]
            [tolgraven.component.restore :as restore]
            [tolgraven.component.hydration :as hydration]
            [tolgraven.browser-resources :as resources]
            [tolgraven.react :as rf]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.render-context :as context]
            [tolgraven.loader :as loader]
            [tolgraven.loader.view :as view]))

(defn options-map [options]
  (if (and (= 1 (count options)) (map? (first options)))
    (first options)
    (apply hash-map options)))

(defc <formatted> [component args committed!]
  (rf/use-layout-effect (fn [] (committed!) js/undefined) #js [])
  (into [component] args))

(defn- make-formatter [committed!]
  ;; Construction is pure. The owned effect releases this gate; only then can
  ;; React.lazy acquire code through the existing parallel CSS/JS loader.
  (let [*release (atom nil)
        gate (js/Promise. (fn [resolve _] (reset! *release resolve)))]
    {:release! #(@*release nil)
     :view (rf/lazy
             (fn []
               (-> gate
                   (.then (fn [_] (loader/acquire-code! :highlight)))
                   (.then (fn [spec]
                            (let [component (get-in spec [:view :code-block])
                                  component (if (var? component) @component component)]
                              #js {:default (rf/reactify-component
                                              (fn [{:keys [args]}]
                                                [<formatted> component args committed!]))}))))))}))

(defc <formatter> [code options]
  (let [[defer? _] (rf/use-state #(and (not context/*server?*) (restore/initial-hydration?)))
        committed! (hydration/use-deferred! defer?)
        [formatter _] (rf/use-state #(make-formatter committed!))
        args (into [code] (mapcat identity options))
        fallback [:pre [:code code]]]
    (rf/use-effect
      (fn []
        (if defer?
          (resources/after-page! (:release! formatter))
          (do ((:release! formatter)) js/undefined)))
      #js [])
    ;; Reagent defc memoizes this boundary independently of outer controls.
    [rf/suspense {:fallback (rf/as-element fallback)}
     (if context/*server?*
       (view/form :highlight :code-block args fallback)
       ;; Keep Clojure arguments opaque at this native React boundary.
       (rf/create-element (:view formatter) #js {:args args}))]))

(rf/reg-event-fx :code-block/copy
  {:args [:cat :string fn?]}
  (fn [_ [_ text complete!]]
    {:code-block/copy {:text text
                       :complete! complete!}}))
(rf/reg-fx :code-block/copy
  (fn [{:keys [text complete!]}]
    (if-let [clipboard (when (exists? js/navigator) (.-clipboard js/navigator))]
      (-> (.writeText clipboard text)
          (.then #(complete! "Copied"))
          (.catch #(complete! "Copy failed")))
      (complete! "Copy unavailable"))))

(defc <code-block>
  [code :- :string & options]
  (let [options (options-map options)
        [wrap? set-wrap!] (rf/use-state false)
        [copy-label set-copy-label!] (rf/use-state "Copy")]
    [:div.code-block {:class (when wrap? "code-block-wrapped")}
     [:div.code-block-controls
      [:button {:type "button"
                :on-click #(rf/dispatch [:code-block/copy code set-copy-label!])}
       copy-label]
      [:button {:type "button"
                :aria-pressed wrap?
                :on-click #(set-wrap! (not wrap?))}
       "Wrap lines"]]
     [<formatter> code options]]))
