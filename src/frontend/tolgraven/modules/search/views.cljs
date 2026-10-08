(ns tolgraven.modules.search.views
  (:require
    [tolgraven.component.registry]
    [tolgraven.modules.link-preview.views :as link-preview]
    [reagent.core :as r]
    [tolgraven.react :as rf]
    [clojure.string :as string]
    [tolgraven.loader]
    [tolgraven.components.ui :as ui]
    [tolgraven.components.image :as img]
    [tolgraven.util :as util :refer [at]])
  (:require-macros [tolgraven.macros :as m]))

(m/defc <button> "Search button, press to show input field..."
  []
  (let [open? (rf/subscribe [:search/open?])]
    [:button.search-ui-btn.noborder.nomargin
     {:name "Search" :title "Search site"
      :on-click (fn [e]
                  (rf/dispatch [:search/state [:open?] (not @open?)])
                  (when-not @open? ; going from closed to open
                    (r/after-render
                     #(js/setTimeout
                       (fn []
                         (util/scroll-to "search-input")
                         (some-> "search-input" util/elem-by-id .focus))
                       100))))}
     [img/<picture> {:src "svg/search-ico.svg"
                   :alt "Search"
                   :style {:width "1.2em" :height "1.2em"
                           :filter "var(--light-to-dark)"}}]]))

(m/defc ^:private <completion-letter>
  {:features [[:appear "slide-in faster"]]}
  [height letter]
  [:div [:span
           {:style {:min-height height}}
           letter]])

(m/defc <completion>
  [query suggestion height]
  (when-not (string/blank? (:match suggestion))
    (let [words (-> (or (:rest suggestion) "")
                    (string/replace #"\n.*" "")
                    (string/replace #"^(.{50}).+" "$1…"))
          [char1 & others] (seq words)]
      [:span.styled-input-autocomplete
       {:style {:white-space :pre-wrap
                :display :inline-flex}}
       [:span.first-char char1]
       (m/for [letter others] ; causes issues with spacing? nice lil zoom effect though, figure out.
         [<completion-letter> height letter])])))

(m/defc <box> "Search input field"
 [collections & {:as args :keys [query-by model height open? opts]
                 :or {height "2em"
                      query-by ["text" "title"]}}]
 (let [suggestions @(rf/subscribe [:search/autocomplete-multi collections])
       query @(rf/subscribe [:search/get-query (first collections)])
       on-change #(doseq [coll collections]
                    (rf/dispatch [:search/search coll % query-by opts false]))
       on-enter #(doseq [coll collections]
                   (rf/dispatch [:search/search coll (:text (first suggestions)) query-by opts false]))
       on-esc #(rf/dispatch [:search/state [:open?] false])]
   [ui/<input-text-styled>
    :id "search-input"
    :query-by query-by
    :model model
    :height height
    :open? open?
    :on-change on-change
    :on-enter on-enter
    :on-esc on-esc
    :completion-fn <completion>
    :suggestions suggestions]))


(m/defc <suggestions> "Display a dropdown of suggested further terms"
  [collections]
  (let [suggestions (rf/subscribe [:search/autocomplete-multi collections])
        last-suggestions (atom nil)
        query (rf/subscribe [:search/get-query "blog-posts"])]
    (fn [collections]
      (let [suggestions' (or @suggestions
                             @last-suggestions)]
        (reset! last-suggestions suggestions')
        [:div.search-autocomplete
         {:class (when (and @(rf/subscribe [:search/open?])
                            (not (string/blank? @query)))
                   "search-autocomplete-open")}
         (m/for [suggestion (drop 1 suggestions') ; rework as map-indexed so can highhlight and pick by keyboard
               :let [{:keys [text html rest match query]} suggestion
                     without-query rest
                     #_(string/replace-first text (re-pattern (str "(?i)" @query)) "")]]
           [:div.search-autocomplete-item
            {:on-click (fn [e]
                         (doseq [coll collections]
                           (rf/dispatch [:search/search coll (str match rest) ["text" "title"]])))}

            [:b query] without-query])]))))


(m/defc ^:private <instant-result>
  {:features [:appear]}
  [{:keys [inner-class component highlights document] :as spec}]
  [:div.search-instant-result
           {:class inner-class}
           [component highlights document]])

(m/defc <instant-result-category> "Wrapper for type of results/collection"
  [collection component inner-class appear-class]
  (if-let [hits (:hits @(rf/subscribe [:search/results-for-query collection]))]
    [:<>
     (when (seq hits)
       (m/for [hit hits
             :let [{:keys [highlights document]} hit
                   {:keys [id text]} document]]
         [<instant-result> {:appear (str appear-class " fast") :inner-class inner-class
                            :component component :highlights highlights :document document}]))]

    [ui/<loading-spinner> true]))

; these should be provided by blog probably? and other respective modules
; could generalize a tiny bit but tricky due to css structure
(m/defc <blog-post-results> "Show hits that are blog posts"
  [highlights document]
  (let [{:keys [id permalink title text user ts]} document]
    [:<>
     [:div.blog-post-header-main
      [:a {:href @(rf/subscribe [:blog/permalink-for-path (or permalink id)])}
       [:h2.blog-post-title title]]
      (m/<> :blog/posted-by {:id id :user user :ts ts})
      (m/<> :blog/tags-list {:post document})]
     (m/for [highlight highlights]
       [link-preview/<md> (:snippet highlight)])]))

(m/defc <blog-comment-results> "Show hits that are blog post comments"
  [highlights document]
  (let [{:keys [id title text user ts]} document]
    [:div
     [:div.blog-comment-border]
     [:section.blog-comment
      (m/<> :user/avatar @(rf/subscribe [:user/user user]))
      [:div.blog-comment-main
       [:h4.blog-comment-title title]
       (m/<> :blog/posted-by {:id id :user user :ts ts})
       (m/for [highlight highlights]
         [:div.blog-comment-text
          [link-preview/<md> (:snippet highlight)]])]]]))

(m/defc <instant-results> "Show results while searching"
  [open?]
  (let []
    (when (and open?
               (not (string/blank? @(rf/subscribe [:search/get-query "blog-posts"]))))
      [:div.search-instant-results
       [:div.blog
        [<instant-result-category> "blog-posts" <blog-post-results> "blog-post" "zoom-y"]]
       [:div.blog-comments>div.blog-comments-inner
        [<instant-result-category> "blog-comments" <blog-comment-results> "blog-comment-around flex" "zoom"]]])))

(m/defc <full-results> "More full complete and whatnot"
  [collection query])

(m/defc <ui> "The search ui. Initially runs over blog-posts and comments, but should later also search docs and hence source-code."
  {:features [:error-boundary]}
  [collection]
  (let [open? (rf/subscribe [:search/open?])
        results-open? (rf/subscribe [:search/results-open?])]
    (fn [collection]
      [:section.search-ui
       {:class (when @open? "search-ui-open")
        :ref #(when % (rf/dispatch [:search/init]))}

       [<box> ["blog-posts" "blog-comments"]
        :model (rf/subscribe [:search/get-query "blog-posts"])
        :open? @open?
        :height (if @open? "2em" "2em")]
       [<suggestions> ["blog-posts" "blog-comments"]]
       [<instant-results> @open?]
       ])))
