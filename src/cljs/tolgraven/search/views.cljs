(ns tolgraven.search.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.views-common :as views]
   [tolgraven.blog.views :as blog]
   [tolgraven.util :as util :refer [at]]))


(defn input "Search input field"
 [collection & {:as args :keys [value opts query-by]}]
 (let [model (r/atom (or value ""))
       div-ref (r/atom nil)]
   (fn [collection &
        {:keys [query-by on-enter placeholder width height]
         :or {width "25em" height "2em"
              query-by ["text" "title"]}}]
   (let [#_event ]
     [:<>
      [:div "SEARCH!!!"]
      [:input.search-input
      {:type "search" ;for clear button ;"text"
       :style {:display "inline-flex" :flex "1 1 auto"
               :min-width width ; how do like "min-width 'chars in str model + 10' up til 200 pixels yada?"
               :min-height height}
       :placeholder placeholder
       ; :autoComplete (string/lower-case placeholder)
       :value       @model
       :ref         #(when % (reset! div-ref %))
       :on-change (fn [e] ; XXX needs debounce I guess
                    (let [new-val (-> e .-target .-value)]
                      (reset! model new-val)
                      (rf/dispatch-sync [:search/search collection @model query-by opts])))
       :on-key-up (fn [e]
                    (case (.-key e)
                      "Enter" (when (and on-enter (not= "" @model))
                                (on-enter @model))
                      ; "Escape" (reset! internal-model @external-model) ;already loses focus automatically when press esc
                      true))}]])))) ;after not before, want to be able to override stuff duh

(defn instant-results "Show results while searching"
  [collection]
  (let [results @(rf/subscribe [:search/results-for-query "blog-posts"])]
    [:section.search-instant-results.blog.fullwide
     (if-let [hits (:hits results)]
      (doall (for [hit hits
           :let [{:keys [highlights document]} hit
                 {:keys [id permalink title text user ts]} document
                 #_snippet #_(-> highlights first :snippet)]]
       ^{:key (str "search-result-" id)}
       [:div.search-instant-result
         [:div.blog-post-header-main
          [:a {:href (blog/make-link (or (:permalink document) (:id document)))}
           [:h2.blog-post-title (:title document)]]
          [blog/posted-by (:id document) (:user document) (:ts document)]
          [blog/tags-list document]]
         (for [highlight highlights]
           ^{:key (str "search-result-highlight-" (:snippet highlight))}
           [ui/md->div (:snippet highlight)])]))
      [ui/loading-spinner true :massive])]))

(defn full-results "More full complete and whatnot"
  [collection query])

(defn ui "The search ui"
  [collection]
  [:section.search-ui
   {:ref #(when % (rf/dispatch [:search/init]))}
   [input collection]
   [instant-results collection]])
