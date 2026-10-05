(ns tolgraven.component.loading
  (:require [reagent.core :as r]
            [clojure.string]
            [tolgraven.react :as rf]
            [tolgraven.supabase.query :as query]
            [tolgraven.components.error :as error]))

(r/defc <spinner> []
  [:span.component-spinner {:role "status" :aria-label "Loading content"}
   [:span.component-spinner__wheel {:aria-hidden true}]
   [:span.sr-only "Loading content…"]])
(declare <placeholder>)

(r/defc <query-fallback>
  "Use the shared component fallback after a real managed-query failure."
  [opts & [placeholder]]
  (if-let [failure @(rf/subscribe [:store/query-error opts])]
    [error/<failure> "content" "query" failure
     #(rf/dispatch [:service-status/retry
                    [:supabase-scoped (pr-str (query/normalize-query opts))]])]
    (or placeholder [<placeholder> {}])))

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

(r/defc <article>
  "Reusable title, author and paragraph skeleton for article-shaped content."
  [& [{:keys [lines avatar?] :or {lines 4 avatar? true}}]]
  [:div.component-skeleton-article {:aria-hidden true}
   [:div.component-skeleton-article__header
    (when avatar? [<avatar>])
    [:div.component-skeleton-article__identity
     [<h1>]
     [:div.component-skeleton-article__byline [<span>] [<span>]]
     [:div.component-skeleton-article__tags [<span>] [<span>] [<span>]]]]
   [:div.component-skeleton-article__body
    (for [paragraph (range 3)]
      ^{:key paragraph} [<lines> {:count lines}])]])

(r/defc <placeholder>
  "A component-shaped loading root, with optional skeleton styling and no spinner."
  [{:keys [loading-tag loading-props loading-prefab classes props]
    :or {loading-tag :div}}]
  (let [kind (case loading-prefab
               (:text :span) "text"
               (:heading :h1 :h2) "heading"
               :avatar "avatar"
               :box "box"
               nil)
        attrs (merge (dissoc loading-props :lines :avatar?) (select-keys props [:class :style :id]))
        class-name (clojure.string/join " " (remove nil? [(:class loading-props) (:class props)
                                                           (if (sequential? classes) (clojure.string/join " " classes) classes)
                                                           (when kind (str "component-skeleton component-skeleton--" kind))]))]
    [loading-tag (assoc attrs :class class-name :aria-busy true
                             :aria-label "Loading content" :role "status")
     (cond
       (= :article loading-prefab) [<article> loading-props]
       (= :lines loading-prefab) [<lines>]
       kind "\u00a0"
       :else nil)]))
