(ns website.browser-resources
  "Acquire optional scripts after hydration, load, pending page work and a painted idle frame."
  (:require [website.browser-resources.page :as page]
            [website.component]
            [website.macros :refer-macros [defc]]
            [website.react :as r]
            [website.browser-resources.schema :as schema]))

(defonce *started? (atom false))

(r/reg-sub :browser-resources/settings
  {:result schema/settings}
  (fn [db _] (get-in db [:browser-resources :settings])))

(def after-page! page/after-page!)

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
