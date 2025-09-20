(ns tolgraven.search.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.blog.views :as blog]
   [tolgraven.util :as util :refer [at]]))

(defn button "Search button, press to show input field..."
  []
  (let [open? (rf/subscribe [:search/open?])]
    [:button.search-ui-btn.noborder.nomargin
     {:name "Search" :title "Search site"
      :on-click (fn [e]
                  (rf/dispatch [:search/state [:open?] (not @open?)])
                  (when-not @open? ; going from closed to open
                    (r/after-render #(js/setTimeout
                      (fn []
                        (js/console.log "ran button")
                        (util/scroll-to "search-input")
                        (some-> "search-input" util/elem-by-id .focus))
                      100))))}
     [:img {:src "svg/search-ico.svg"
            :style {:width "1.2em" :height "1.2em"
                    :filter "var(--light-to-dark)"}}]]))

(defn completion
  [query suggestion height]
  (when-not (string/blank? (:match suggestion))
    (let [words (some-> (str #_(-> (get suggestion :query "")
                             (string/replace  #"\w" " ")) ; get as many spaces as there were letters
                             (get suggestion :rest nil))
                        (string/replace #"\n.*" "")
                        (string/replace #"^(.{0,40})(.*)" "$1...")
                        (string/replace query "")
                        seq)
          [char1 others] [(first words) (rest words)]]
      [:span.styled-input-autocomplete
       {:style {:white-space :pre-wrap
                :display :inline-flex}}
       [:span.first-char char1]
       (for [letter others] ; causes issues with spacing? nice lil zoom effect though, figure out.
         [ui/appear-anon "slide-in faster"
          [:span
           {:style {:min-height height}}
           letter]])])))

(defn box "Search input field"
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
   [ui/input-text-styled
    :id "search-input"
    :query-by query-by
    :model model
    :height height
    :open? open?
    :on-change on-change
    :on-enter on-enter
    :on-esc on-esc
    :completion-fn completion
    :suggestions suggestions]))


(defn suggestions "Display a dropdown of suggested further terms"
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
         (for [suggestion (drop 1 suggestions') ; rework as map-indexed so can highhlight and pick by keyboard
               :let [{:keys [text html rest match query]} suggestion
                     without-query rest
                     #_(string/replace-first text (re-pattern (str "(?i)" @query)) "")]]
           [:div.search-autocomplete-item
            {:on-click (fn [e]
                         (doseq [coll collections]
                           (rf/dispatch [:search/search coll (str match rest) ["text" "title"]])))}

            [:b query] without-query])]))))


(defn instant-result-category "Wrapper for type of results/collection"
  [collection component inner-class appear-class]
  (if-let [hits (:hits @(rf/subscribe [:search/results-for-query collection]))]
    [:<>
     (when (seq hits)
       (for [hit hits
             :let [{:keys [highlights document]} hit
                   {:keys [id text]} document]]
         ^{:key (str "search-result-" collection "-" id)}
         [ui/appear-merge (str appear-class " fast")
          [:div.search-instant-result
           {:class inner-class}
           [component highlights document]]]))]

    [ui/loading-spinner true]))


; these should be provided by blog probably? and other respective modules
; could generalize a tiny bit but tricky due to css structure
(defn blog-post-results "Show hits that are blog posts"
  [highlights document]
  (let [{:keys [id permalink title text user ts]} document]
    [:<>
     [:div.blog-post-header-main
      [:a {:href @(rf/subscribe [:blog/permalink-for-path (or permalink id)])}
       [:h2.blog-post-title title]]
      [blog/posted-by id user ts]
      [blog/tags-list document]]
     (for [highlight highlights]
       ^{:key (str "search-result-highlight-" (:snippet highlight))}
       [ui/md->div (:snippet highlight)])]))

(defn blog-comment-results "Show hits that are blog post comments"
  [highlights document]
  (let [{:keys [id title text user ts]} document]
    [:div
     [:div.blog-comment-border]
     [:section.blog-comment
      [ui/user-avatar @(rf/subscribe [:user/user user])]
      [:div.blog-comment-main
       [:h4.blog-comment-title title]
       [blog/posted-by id user ts] 
       (for [highlight highlights]
         ^{:key (str "search-result-highlight-" (:snippet highlight))}
         [:div.blog-comment-text
          [ui/md->div (:snippet highlight)]])]]]))

(defn instant-results "Show results while searching"
  [open?]
  (let []
    (when (and open?
               (not (string/blank? @(rf/subscribe [:search/get-query "blog-posts"]))))
      [:div.search-instant-results
       [:div.blog
        [instant-result-category "blog-posts" blog-post-results "blog-post" "zoom-y"]]
       [:div.blog-comments>div.blog-comments-inner
        [instant-result-category "blog-comments" blog-comment-results "blog-comment-around flex" "zoom"]]])))

(defn full-results "More full complete and whatnot"
  [collection query])

(defn ui "The search ui. Initially runs over blog-posts and comments, but should later also search docs and hence source-code."
  [collection]
  (let [open? (rf/subscribe [:search/open?])
        results-open? (rf/subscribe [:search/results-open?])]
    (fn [collection]
      [:section.search-ui
       {:class (when @open? "search-ui-open")
        :ref #(when % (rf/dispatch [:search/init]))}
       
       [box ["blog-posts" "blog-comments"]
        :model (rf/subscribe [:search/get-query "blog-posts"])
        :open? @open?
        :height (if @open? "2em" "2em")]
       [suggestions ["blog-posts" "blog-comments"]]
       [instant-results @open?]
       ])))
