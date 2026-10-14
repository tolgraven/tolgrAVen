(ns website.component.loading
  (:require [website.react :as r]
            [clojure.string]
            [website.component.visibility :as visibility]
            [website.supabase.query :as query]
            [website.components.error :as error]))

(defonce render-context (r/create-context false))

(defn- reserved-size [element]
  (let [style (js/getComputedStyle element)
        unit (js/parseFloat (.-fontSize style))
        height (js/parseFloat (.-height style))
        height-valid? (and (pos? unit) (js/isFinite height) (>= height 0))]
    (cond-> {}
      height-valid? (assoc :height (str (/ height unit) "em"))
      (not height-valid?) (assoc :min-height "1em")
      (and (pos? unit) (js/isFinite (js/parseFloat (.-width style)))
           (.startsWith (.-display style) "inline"))
      (assoc :width (str (/ (js/parseFloat (.-width style)) unit) "em")))))

(defn- use-visible [attrs root-key]
  ;; This core adapter cannot depend on the full defc runtime. One outer loading
  ;; owner observes visibility; its skeleton context prevents one observer per
  ;; line. React owns both the reserved geometry and removal of expensive children.
  (let [nested? (r/use-context render-context)
        *element (r/use-ref nil)
        [state set-state!] (r/use-state {:visible? true})
        caller-ref (:ref attrs)
        ref (r/use-memo #(r/compose-refs *element caller-ref) [*element caller-ref])]
    (r/use-effect
      (fn []
        (when (and (not nested?) (exists? js/window) (.-current *element))
          (let [element (.-current *element)
                *in-view? (atom true)
                update! (fn []
                          (let [visible? (and @*in-view? (not (.-hidden js/document)))
                                geometry (when-not visible? (reserved-size element))]
                            (set-state! (fn [current]
                                          (if (= visible? (:visible? current)) current
                                              {:visible? visible? :geometry geometry})))))
                observer (visibility/setup {:on-change #(do (reset! *in-view? %) (update!))
                                            :once? false
                                            :threshold 0})]
            ;; The listener registry depends on the component runtime. This
            ;; document adapter stays below that cycle and releases its listener.
            (.addEventListener js/document "visibilitychange" update!)
            ((:mount! observer) element)
            (update!)
            (fn []
              ((:stop! observer))
              (.removeEventListener js/document "visibilitychange" update!)))))
      [nested? root-key])
    [(:visible? state)
     (cond-> (assoc attrs :ref ref)
       (not (:visible? state))
       (assoc :data-skeleton-hidden true :style (merge (:style attrs) (:geometry state))))]))

(r/defc <spinner> []
  (let [[visible? attrs] (use-visible {:role "status" :aria-label "Loading content"} :span)]
    [:span.component-spinner attrs
     (when visible?
       [:<> [:span.component-spinner__wheel {:aria-hidden true}]
        [:span.sr-only "Loading content…"]])]))
(declare <placeholder>)

(r/defc <rendered>
  "Render the ordinary component tree as an inert, CSS-shaped skeleton.
   Sample arguments supply text lengths when content is not available yet."
  [{:keys [form class]}]
  (let [[visible? attrs] (use-visible {:class (str "component-render-skeleton " class)
                                     :aria-busy true :aria-label "Loading content" :inert true} :div)]
    [:> (r/context-provider render-context) {:value true}
     [:div attrs (when visible? form)]]))

(r/defc <query-fallback>
  "Use the shared component fallback after a real managed-query failure."
  [opts & [placeholder]]
  (if-let [failure @(r/subscribe [:store/query-error opts])]
    [error/<failure> "content" "query" failure
     #(r/dispatch [:service-status/retry
                    [:supabase-scoped (pr-str (query/normalize-query opts))]])]
    (or placeholder [<placeholder> {}])))

(defn- element [tag kind options]
  (let [[visible? attrs] (use-visible
                          (-> options
                              (update :class #(str "component-skeleton component-skeleton--" kind " " %))
                              (assoc :aria-hidden true)) tag)]
    [tag attrs (when visible? "\u00a0")]))
(r/defc <span> [& [options]] (element :span "text" options))
(r/defc <h1> [& [options]] (element :h1 "heading" options))
(r/defc <h2> [& [options]] (element :h2 "heading" options))
(r/defc <box> [& [options]] (element :div "box" options))
(r/defc <avatar> [& [options]] (element :span "avatar" options))
(r/defc <lines> [& [{:keys [count] :or {count 3} :as options}]]
  (let [[visible? attrs] (use-visible {:class "component-skeleton-lines" :aria-hidden true} :div)]
    [:> (r/context-provider render-context) {:value true}
     (into [:div attrs]
           (when visible?
             (for [i (range count)] ^{:key i} [<span> (dissoc options :count)])))]))

(r/defc <article>
  "Reusable title, author and paragraph skeleton for article-shaped content."
  [& [{:keys [lines avatar?] :or {lines 4 avatar? true}}]]
  (let [[visible? attrs] (use-visible {:aria-hidden true} :div)]
    [:> (r/context-provider render-context) {:value true}
     [:div.component-skeleton-article attrs
      (when visible?
        [:<>
         [:div.component-skeleton-article__header
          (when avatar? [<avatar>])
          [:div.component-skeleton-article__identity
           [<h1>]
           [:div.component-skeleton-article__byline [<span>] [<span>]]
           [:div.component-skeleton-article__tags [<span>] [<span>] [<span>]]]]
         [:div.component-skeleton-article__body
          (for [paragraph (range 3)]
            ^{:key paragraph} [<lines> {:count lines}])]])]]))

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
                                                           (when kind (str "component-skeleton component-skeleton--" kind))]))
        [visible? attrs'] (use-visible (assoc attrs :class class-name :aria-busy true
                                                  :aria-label "Loading content" :role "status") loading-tag)]
    [:> (r/context-provider render-context) {:value true}
     [loading-tag attrs'
      (when visible?
        (cond
          (= :article loading-prefab) [<article> loading-props]
          (= :lines loading-prefab) [<lines>]
          kind "\u00a0"
          :else nil))]]))
