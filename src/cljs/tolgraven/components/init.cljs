(ns tolgraven.components.init
  (:require [tolgraven.react :as rf]))

(defn <fallback> [retry!]
  (let [{:keys [status]} @(rf/subscribe [:state [:page-init]])]
    (when (= :failed status)
      [:div#page-init-error {:role "alert"}
       [:p "The page could not initialize. Check your connection and retry. The failure has been recorded in the webpage log."]
       [:button {:on-click (fn [_] (retry!))} "Retry"]])))
