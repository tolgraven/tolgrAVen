(ns tolgraven.components.ui.loading
  (:require [tolgraven.component.registry]
            [tolgraven.macros :as m :include-macros true]
            [tolgraven.util :as util :refer [at]]))


(m/defc ^:private <spinner-icon>
  {:features [[:appear "zoom slow"]]}
  [kind]
  [:div [:i.loading-spinner
       {:class (str "fa fa-spinner fa-spin"
                    (when (= kind :massive)
                      " loading-spinner-massive"))}]])

(m/defc ^:private <timeout-icon>
  {:features [[:appear "opacity slow"]]}
  []
  [:div [:i.loading-timeout
         {:class (str "fa fa-solid fa-hexagon-exclamation")}]])

(m/defc <loading-spinner> [model kind & [attrs]]
  (if (and (at model)
           (not= :timeout (at model))) ;should it be outside so not put anything when not loading? or better know element goes here
    [:div.loading-container
     attrs
     [(if (not= kind :still)
                :div.loading-wiggle>div.loading-wiggle-z>div.loading-wiggle-y
                :<>)
     [<spinner-icon> kind]]]
    (when (= :timeout (at model))
      [:div.loading-container
       [<timeout-icon>]])))
