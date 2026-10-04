(ns tolgraven.component.loading
  (:require [reagent.core :as r]))

(r/defc <spinner> []
  [:span.component-spinner {:role "status" :aria-label "Loading content"}
   [:span.component-spinner__wheel {:aria-hidden true}]
   [:span.sr-only "Loading content…"]])
(defn- element [tag kind options]
  [tag (-> options
           (update :class #(str "component-skeleton component-skeleton--" kind " " %))
           (assoc :aria-hidden true)) "\u00a0"])
(r/defc <span> [& [options]] (element :span "text" options))
(r/defc <h1> [& [options]] (element :h1 "heading" options))
(r/defc <h2> [& [options]] (element :h2 "heading" options))
(r/defc <box> [& [options]] (element :div "box" options))
(r/defc <avatar> [& [options]] (element :span "avatar" options))
(r/defc <lines> [& [{:keys [count] :or {count 3} :as options}]]
  (into [:div {:class "component-skeleton-lines" :aria-hidden true}]
        (for [i (range count)] ^{:key i} [<span> (dissoc options :count)])))
