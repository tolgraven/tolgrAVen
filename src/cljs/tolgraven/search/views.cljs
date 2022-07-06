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
                  (rf/dispatch [:search/state [:open?]
                                (not @open?)])
                  (when-not @open? ; going from closed to open
                    (rf/dispatch [:focus-element "search-input"])))}
     [:img {:src "svg/search-ico.svg"
            :style {:width "1.2em" :height "1.2em"
                    :filter "invert(100%)"}}]]))

(defn input "Search input field"
 [collections & {:as args :keys [query-by model on-enter placeholder
                                 width height open? opts]}]
 (let [external-model (r/atom model)
       internal-model (r/atom (or @model ""))
       div-ref (r/atom nil)]
   (fn [collections &
        {:keys [query-by model on-enter placeholder width height open? opts]
         :or {height "2em"
              query-by ["text" "title"]}}]
     (let [suggestions @(rf/subscribe [:search/autocomplete-multi collections])
           suggestion-str (string/join " " (:match suggestions))
           suggestion (first suggestions)
           query @(rf/subscribe [:search/get-query (first collections)])]
   [:div.search-input-container
        
     [:input#search-input.search-input ;problem if multiple search boxes on same page tho
      {:type "search"
       :style {:min-width width :max-width width
               :min-height height :max-height height
               :padding (when (or (zero? width) (zero? height)) 0)
               :border (when (or (zero? width) (zero? height)) 0)}
       :placeholder (or placeholder "Search") ; might want "Search for..." like
       :autoComplete "off"
       :max 40
       :value       (if (and (not (string/blank? (:match suggestion)))
                             (not= suggestion-str query)
                             #_(not (re-find #"\s" query)))
                      (str query #_(:match suggestion)
                           #_(re-find #"\s" query))
                      @internal-model)
       :ref         #(when % (reset! div-ref %))
       :on-change (fn [e] ; XXX needs debounce I guess
                    (let [new-val (-> e .-target .-value)]
                      (reset! internal-model new-val)
                      ; (reset! external-model @internal-model)
                      (doseq [coll collections]
                        (rf/dispatch [:search/search coll new-val query-by opts false]))))
       :on-key-up (fn [e]
                    (case (.-key e)
                      "Enter" (when (not= "" @model)
                                ; (set! (.-value @div-ref) (:text suggestion))
                                (reset! internal-model (:text suggestion))
                                (doseq [coll collections]
                                  (rf/dispatch [:search/search coll (:text suggestion) query-by opts false])))
                      "Escape" (do (.stopPropagation e) ; can't seem to stop it from blanking query hmm
                                   (.preventDefault e)
                                   (rf/dispatch [:search/state [:open?] false]))
                      true))}]
     (when-not (string/blank? (:match suggestion))
      [:div.search-input.search-input-autocomplete.form-control
       {:style {:min-height height}}
       [:pre.search-input-suggestion
        (-> (str (-> (get suggestion :query "")
                     (string/replace  #"\w" " ")) ; get as many spaces as there were letters
                 (get suggestion :rest ""))
            (string/replace #"\n.*" "")
            (string/replace #"^(.{0,30})(.*)" "$1..."))]])
     [:span.search-input-info "BETA"]]))))

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
  [collection component]
  (if-let [hits (:hits @(rf/subscribe [:search/results-for-query collection]))]
    [component hits]
    [ui/loading-spinner true]))


(defn blog-post-results "Show hits that are blog posts"
  [hits]
  [:div.blog
   (doall
   (for [hit hits
         :let [{:keys [highlights document]} hit
               {:keys [id permalink title text user ts]} document]]
     ^{:key (str "search-result-post-" id)}
     [ui/appear-merge "zoom-y fast"
      [:div.search-instant-result.blog-post
       [:div.blog-post-header-main
        [:a {:href @(rf/subscribe [:blog/permalink-for-path (or permalink id)])}
         [:h2.blog-post-title title]]
        [blog/posted-by id user ts]
        [blog/tags-list document]]
       (for [highlight highlights]
         ^{:key (str "search-result-highlight-" (:snippet highlight))}
         [ui/md->div (:snippet highlight)])]]))])

(defn blog-comment-results "Show hits that are blog post comments"
  [hits]
  (when (seq hits)
    [:div.blog-comments>div.blog-comments-inner
     (doall
      (for [hit hits
            :let [{:keys [highlights document]} hit
                  {:keys [id title text user ts]} document]]
        ^{:key (str "search-result-comment-" id)}
        [ui/appear-merge "zoom fast"
         [:div.search-instant-result.blog-comment-around.flex
          [:div.blog-comment-border]
          [:section.blog-comment
           [ui/user-avatar @(rf/subscribe [:user/user user])]
           [:div.blog-comment-main
            [:h4.blog-comment-title title]
            [blog/posted-by id user ts] 
            (for [highlight highlights]
              ^{:key (str "search-result-highlight-" (:snippet highlight))}
              [:div.blog-comment-text
               [ui/md->div (:snippet highlight)]])]]]]))]))

(defn instant-results "Show results while searching"
  [open?]
  (let []
    (when (and open?
               (not (string/blank? @(rf/subscribe [:search/get-query "blog-posts"]))))
      [:section.search-instant-results
       [instant-result-category "blog-posts" blog-post-results]
       [instant-result-category "blog-comments" blog-comment-results]])))

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
       
       [input ["blog-posts" "blog-comments"]
        :model (rf/subscribe [:search/get-query "blog-posts"])
        :open? @open?
        :height (if @open? "2em" 0)]
       [suggestions ["blog-posts" "blog-comments"]]
       [instant-results @open?]
       ])))
