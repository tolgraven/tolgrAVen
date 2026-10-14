(ns website.components.service-status
  (:require [website.macros :refer-macros [defc]]
            [website.component.registry]
            [website.render-context :as context]
            [website.service-status :as status]))

(defc <notices> []
  [:aside {:aria-label "Service notifications" :aria-live "polite"
           :style {:position "sticky" :top "var(--header-height-current, 5rem)" :z-index 90
                   :max-height "40vh" :overflow-y "auto"}}
   (for [[id {:keys [title message retry!]}] (when @context/*interactive? @status/*failures)]
     ^{:key (pr-str id)}
     [:div {:role "alert" :style {:padding "1rem" :background "#392a24" :color "#fff"}}
      [:strong title] [:p message]
      (when retry! [:button {:on-click (fn [_] (retry!))} "Retry"])])])
