(ns tolgraven.service-status
  (:require [reagent.core :as r]
            [re-frame.core :as rf]))

(defonce *failures (r/atom {}))

(defn fail! [id title message retry!]
  ;; One log/notification per outage, not one per automatic retry.
  (when-not (contains? @*failures id)
    (rf/dispatch [:diag/new :error title message]))
  (swap! *failures assoc id {:title title :message message :retry! retry!}))

(defn recover! [id] (swap! *failures dissoc id))

(defn within! [promise milliseconds]
  (js/Promise.
   (fn [resolve reject]
     (let [timer (js/setTimeout #(reject (js/Error. "Service request timed out")) milliseconds)]
       (-> (js/Promise.resolve promise)
           (.then (fn [value] (js/clearTimeout timer) (resolve value)))
           (.catch (fn [error] (js/clearTimeout timer) (reject error))))))))

(defn <notices> []
  [:aside {:aria-label "Service notifications" :aria-live "polite"
           :style {:position "sticky" :top 0 :z-index 10000}}
   (for [[id {:keys [title message retry!]}] @*failures]
     ^{:key (pr-str id)}
     [:div {:role "alert" :style {:padding "1rem" :background "#392a24" :color "#fff"}}
      [:strong title] [:p message]
      (when retry! [:button {:on-click (fn [_] (retry!))} "Retry"])])])
