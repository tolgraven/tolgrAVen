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
                    (r/after-render #(js/setTimeout
                      (fn []
                        (some-> "search-input" util/elem-by-id .focus))
                      0))))}
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
    :query-by query-by
    :model model
    :height height
    :open? open?
    :on-change on-change
    :on-enter on-enter
    :on-esc on-esc
    :completion-fn completion
    :suggestions suggestions]))

(defn box-orig "Search input field"
 [collections & {:as args :keys [query-by model on-enter placeholder
                                 width height open? opts]}]
 (let [internal-model (r/atom (or @model ""))
       char-width 0.61225
       div-ref (r/atom nil)
       caret (r/atom 0)
       selection-end (r/atom 0)
       caret-watch (add-watch internal-model :caret-watch
                              (fn [rf k old new]
                                (reset! caret (inc (count new)))
                                (reset! selection-end (inc (count new)))))
       set-caret (fn [target]
                   (reset! caret (.-selectionStart target))
                   (reset! selection-end (.-selectionEnd target)))
       move-caret (fn [move]
                    (swap! caret + move))]
   (fn [collections &
        {:keys [query-by model on-enter placeholder width height open? opts]
         :or {height "2em"
              query-by ["text" "title"]}}]
     (let [suggestions @(rf/subscribe [:search/autocomplete-multi collections])
           suggestion-str (string/join " " (:match suggestions))
           suggestion (first suggestions)
           query @(rf/subscribe [:search/get-query (first collections)])
           caret-pos (str (* char-width @caret) "em")
           selection-len (* char-width (abs (- @caret @selection-end)))
           caret-height (* 1.6 (max 0.(- 1.0 (* 0.03 selection-len) )))]
   [:div.search-input-container
    {:class (when-not open? "closed")}
    
    [:div.search-query-visible
     {:style {:height height }}
     
     [:label.search-caret.nomargin.nopadding
      {:style {:position :absolute
               :width (str (max char-width selection-len) "em")
               :height (str caret-height "em")
               :left caret-pos
               :top (str (- (/ (- 1.6 caret-height #_"-0.1em") 2) 0.15) "em")}}
       "_"]
     [:label.search-caret-under.nomargin.nopadding
      {:style {:position :absolute
               :left caret-pos
               :top "0.1em"
               :animation (when-not (zero? selection-len)
                            "unset")}}
      "_"]
     
     (when-not (string/blank? query)
       [:span {:style {:white-space :pre-wrap
                       :display :inline-flex}}
        #_query
        (for [letter query] ; causes issues with spacing? nice lil zoom effect though, figure out.
          [ui/appear-anon "zoom fast"
           [:span.search-letter letter]])])
     
     [completion query suggestion height]]
     
     [:input#search-input.search-input ;problem if multiple search boxes on same page tho
      {:type "search"
       :incremental true
       :style {:opacity 0
               :width width ;:min-width width :max-width width
               ; :height height 
               :min-height height
               :max-height height
               :padding (when (or (zero? width) (zero? height)) 0)
               :border (when (or (zero? width) (zero? height)) 0)}
       :placeholder (or placeholder "Search") ; might want "Search for..." like
       :autoComplete "off"
       :max 40
       :value      @internal-model
       :ref         #(when % (reset! div-ref %))
       :on-change (fn [e] ; XXX needs debounce I guess
                    (let [new-val (-> e .-target .-value)]
                      (reset! internal-model new-val)
                      (doseq [coll collections]
                        (rf/dispatch [:search/search coll new-val query-by opts false])))
                    (set-caret (.-target e)))
       :on-search (fn [e] ; this is da debounce! apparently recommended against. also not working anyways hahah
                    (let [new-val (-> e .-target .-value)]
                      ; (reset! internal-model new-val)
                      (doseq [coll collections]
                        (rf/dispatch [:search/search coll new-val query-by opts false]))))
       
       ; :on-key-down (fn [e] (set-caret (.-target e)))
       :on-click (fn [e] (set-caret (.-target e)))
       :on-touch-start (fn [e] (set-caret (.-target e)))
       :on-touch-move (fn [e]
                        (reset! selection-end (-> e .-target .-selectionEnd))) ; actually just set selection I suppose
       :on-touch-end (fn [e]
                       (reset! selection-end (-> e .-target .-selectionEnd)))
       ; how handle moving caret using mobile keyboard tap and hold and swipe on spacebar?
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
                      "Left" (move-caret -1)
                      "Right" (move-caret 1)
                      true)
                    #_(fn [e] (set-caret (.-target e))))}]
     
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
