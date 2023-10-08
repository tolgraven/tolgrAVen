(ns tolgraven.github.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.util :as util :refer [at]]))


(defn commit "Show a single commit with highlighted diff etc..."
  [sha close]
  (let [commit @(rf/subscribe [:github/commit sha])]
    [ui/appear-merge "zoom-y"
     [:div.github-commit-full
     close
     (for [file (:files commit)
           :let [suffix (-> (:filename file)
                            (string/split #"\.")
                            last)]]
       [:div
        {:ref #(when % (rf/dispatch [:run-highlighter! %]))} ;screws up live preview :( jumpy
        [:code
         (:filename file)]
        [:p
         [:span {:style {:color "var(--green)"}}
          (util/pluralize (:additions file) " addition")] ", "
         [:span {:style {:color "var(--red)"}}
          (util/pluralize (:deletions file) " deletion")] " = "
         [:span {:style {:color "var(--orange)"}}
          (util/pluralize (:changes file)   " change")] "."
         (when (= 0 (:additions file) (:deletions file) (:changes file))
           [:span {:style {:color "var(--blue)"}}
            ".. word on the street has it the file IS the change!"])]
        [:div
         (let [hunks (some-> (:patch file)
                             (string/split #"(?m)^@@.*@@")
                             rest)
               headers (some-> (:patch file)
                               (string/replace #"(?m)(@@\n)(.|\n)*(^@@)?" "$1$3")
                               (string/split-lines))]
           (into [:<>]
            (for [[hunk header] (partition 2 2 (interleave hunks headers))
                  :let [diff (string/replace hunk #"(?m)(^.).*" "$1")
                        added (map-indexed (fn [i line]
                                             (case
                                               (string/starts-with? "+" line) i))
                                           (string/split-lines diff))
                        deleted (map-indexed (fn [i line]
                                             (case
                                               (string/starts-with? "-" line) i))
                                           (string/split-lines diff)) ]]
              
             [:div
              [:p header]
              [:div.flex
               [:pre.diff diff]
               [:pre.linenum
                (let [start  (->> (re-find #"-(\d*)," header)
                                  second
                                  js/parseInt)
                      len (-> (string/split-lines hunk)
                              count
                              #_(- (count (filter some? deleted))))
                      lines (->> (range start (+ start len))
                                 (string/join "\n"))]
                lines)]
               [:pre
                {:class (case suffix
                          ("clj", "cljs") "clojure"
                          "scss"          "css"
                          "")}
                (-> hunk
                    (string/replace #"(?m)^." ""))]]])))]])]]))

(defn loading "Lazy load more on scroll to bottom, with a button as fallback for the poors"
  [user repo]
  (fn [user repo]
   [:div.github-loading
   [ui/lazy-load
    [:github/fetch-commits-next user repo]
    true]
   [:h2 "Loaded " (count @(rf/subscribe [:github/commits]))]
   [:h3 "Scrolling down should load more..."]
   [:div {:style {:padding "var(--space)"}}
    [ui/loading-spinner true :still]]
   [:button {:style {:margin-top "var(--space-lg)"}
             :on-click #(do (rf/dispatch [:github/fetch-commits-next user repo]))}
    "...or you can click here"]]))

(defn commits "List Github commits for this repo"
  []
  (let [from (rf/subscribe [:github/get-from])
        commits (rf/subscribe [:github/filter-by])
        amount (rf/subscribe [:github/commit-count])
        view (r/atom :commits)
        main-view-position (r/atom 0)
        ref-fn #(when %
                  (when-let [el (util/elem-by-id "github-commits-box")]
                    (if (= @view :commits)
                      (set! (.-scrollTop el) @main-view-position)
                      (set! (.-scrollTop el) 0))))
        url (rf/subscribe [:github/website-url])]
   (fn []
    [:<>
     [:section.github-commits.covering-2
     
     [:h2 [:i.fab.fa-github]" " @amount " commits to "
      [:span
       [:a {:title "The repo" :href @url}
        "this website"]]]
        
     [:div#github-commits-box.github-commits-inner
      [ui/input-text :id "github-commits-filter"
       :input-type "input.search"
       :placeholder "Search commits"
       :width "100%"
       :height "2em"
       :path [:form-field [:github :search]]]
      [:div#github-commits-main
       {:ref ref-fn} ; restore scroll when displaying details
       (when (= @view :commits)
        [:div {:style {:text-align "center"
                       :padding "1em"}}
          "Click a commit for details and diff"])
      (if (= @view :commits)
       (for [{:keys [commit author html_url sha sha7 message date clock ts] :as item} @commits
              :let [[info subtitle title] message]]
            ^{:key (str "github-commit-" ts)}
        [ui/appear-merge "slide-in slow"
        [:div.github-commit.flex
         {:on-click #(do (rf/dispatch [:github/fetch-commit (first @from) (second @from) sha])
                         (reset! main-view-position
                                 (.-scrollTop (util/elem-by-id "github-commits-box")))
                         (reset! view sha))}
         [:img.user-avatar.center-content {:src (:avatar_url author)}]
         [:div.github-commit-details
          [:span.github-commit-time date]
          [:span.github-commit-time clock]
          [:a {:href html_url}
           [:span.github-commit-sha sha7]]

          [:div.github-commit-message
           [:div.info info]]]
          (if title
             [:div.github-commit-titles
              [:span.subtitle
               {:style {:cursor "pointer"}
                :on-click (fn [e] (.stopPropagation e)
                            (rf/dispatch [:form-field [:github :search] subtitle]))}
               subtitle]
              [:i.fa.fa-solid.fa-arrow-left]
              [:span.title
               {:style {:cursor "pointer"}
                :on-click (fn [e] (.stopPropagation e)
                            (rf/dispatch [:form-field [:github :search] title]))}
               title]]
             (when subtitle
               [:div.github-commit-titles
                [:span.title
                 {:style {:cursor "pointer"}
                  :on-click (fn [e] (.stopPropagation e)
                              (rf/dispatch [:form-field [:github :search] subtitle]))}
                 subtitle]]))]])

          [:div
            [commit @view [ui/close #(reset! view :commits)]]])
      
     (when (= @view :commits)
      [loading (first @from) (second @from)])]]]
     [ui/fading :dir "bottom"]])))

