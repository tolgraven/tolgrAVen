(ns tolgraven.component.loading
  (:require [reagent.core :as r]
            [tolgraven.react :as rf]
            [tolgraven.supabase.query :as query]
            [tolgraven.components.error :as error]))

(r/defc <spinner> []
  [:span.component-spinner {:role "status" :aria-label "Loading content"}
   [:span.component-spinner__wheel {:aria-hidden true}]
   [:span.sr-only "Loading content…"]])
(r/defc <query-fallback>
  "Use the shared component fallback after a real managed-query failure."
  [opts]
  (if-let [failure @(rf/subscribe [:store/query-error opts])]
    [error/<failure> "content" "query" failure
     #(rf/dispatch [:service-status/retry
                    [:supabase-scoped (pr-str (query/normalize-query opts))]])]
    [<spinner>]))

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
