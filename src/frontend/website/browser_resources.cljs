(ns website.browser-resources
  "Acquire optional scripts after hydration, load, pending page work and a painted idle frame."
  (:require [website.component.restore :as restore]
            [website.component]
            [website.macros :refer-macros [defc]]
            [website.react :as r]
            [website.listener :as listener]
            [website.render-context :as context]
            [website.browser-resources.schema :as schema]))

(defonce *started? (atom false))

(r/reg-sub :browser-resources/settings
  {:result schema/settings}
  (fn [db _] (get-in db [:browser-resources :settings])))

(defn after-page! [load!]
  (let [*active? (atom true)
        *frame (atom nil)
        *idle (atom nil)
        *timer (atom nil)
        *stop-listeners (atom nil)]
    (letfn [(idle! []
              (when @*active?
                (if (and @context/*interactive?
                         (not (:hydrate? @restore/*context))
                         (restore/layout-ready?))
                  (load!)
                  (reset! *timer (js/setTimeout painted! 100)))))
            (queue-idle! []
              (when @*active?
                (if (.-requestIdleCallback js/window)
                  (reset! *idle (js/requestIdleCallback idle!))
                  (reset! *timer (js/setTimeout idle! 200)))))
            (painted! []
              (when @*active?
                (reset! *frame
                        (js/requestAnimationFrame
                         (fn []
                           (when @*active?
                             (reset! *frame (js/requestAnimationFrame queue-idle!))))))))]
      (if (= "complete" (.-readyState js/document))
        (painted!)
        (reset! *stop-listeners
          (listener/listen! :browser-resources
            [{:target "window", :event "load", :handler painted!, :once? true}])))
      (fn []
        (reset! *active? false)
        (when-let [stop! @*stop-listeners] (stop!))
        (when @*frame (js/cancelAnimationFrame @*frame))
        (when @*idle (js/cancelIdleCallback @*idle))
        (when @*timer (js/clearTimeout @*timer))))))

(defn- configure-analytics! [google-id]
  (when (compare-and-set! *started? false true)
    ;; The SDK expects a native queue/arguments object. Script acquisition is
    ;; ordinary Hiccup below; only its external configuration lives here.
    (aset js/window "dataLayer" (or (aget js/window "dataLayer") #js []))
    (aset js/window "gtag"
          ;; GA consumes the native arguments object, not a CLJS sequence.
          (fn [] (.push (aget js/window "dataLayer") (js* "arguments"))))
    ((aget js/window "gtag") "js" (js/Date.))
    ((aget js/window "gtag") "config" google-id)))

(defc <analytics>
  {:depends [{:source :url
              :url "/api/browser-resources"
              :into [:browser-resources :settings]}]
   :loading [:div {:hidden true}]}
  []
  (let [settings @(r/subscribe [:browser-resources/settings])
        google-id (get-in settings [:analytics :google-id])]
    ;; The layout commit configures the SDK queue before the browser can execute
    ;; the async script. This setup never downloads or changes rendered markup.
    (r/use-layout-effect
      (fn [] (when google-id (configure-analytics! google-id)))
      [google-id])
    (if google-id
      [:script {:async true
                :fetch-priority "low"
                :src (str "https://www.googletagmanager.com/gtag/js?id=" google-id)}]
      [:div {:hidden true}])))

(defc <deferred> [content]
  :let [*ready? (r/atom false)]
  (r/use-effect
    (fn []
      (let [*timer (atom nil)
            cancel-page! (after-page!
                           #(reset! *timer (js/setTimeout (fn [] (reset! *ready? true)) 200)))]
        (fn []
          (cancel-page!)
          (when @*timer (js/clearTimeout @*timer)))))
    [])
  (if @*ready?
    content
    [:div {:hidden true}]))
