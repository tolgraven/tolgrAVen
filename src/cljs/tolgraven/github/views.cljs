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
        [:p (util/pluralize (:additions file) " addition") ", "
            (util/pluralize (:deletions file) " deletion") ", "
            (util/pluralize (:changes file)   " change") "."]
        [:div
         (let [hunks (-> (:patch file)
                         (string/split #"(?m)^@@.*@@")
                         rest)
               headers (-> (:patch file)
                           (string/replace #"(?m)(@@\n)(.|\n)*(^@@)?" "$1$3")
                           (string/split-lines))]
           ; (util/log :error "fo" (count hunks) (count headers))
           (into [:<>]
            (for [[hunk header] (partition 2 2 (interleave hunks headers))]
             [:div
              [:p header]
              [:div.flex
               [:pre.diff
                (string/replace hunk #"(?m)(^.).*" "$1")]
               [:pre.linenum
                (let [start  (->> (re-find #"-(\d*)," header)
                                  second
                                  js/parseInt)
                      len (-> (string/split-lines hunk)
                              count)
                      lines (->> (range start (+ start len))
                                 (string/join "\n"))]
                lines)]
               [:pre
                {:class (case suffix
                          ("clj", "cljs") "clojure"
                          "scss"          "css"
                          "")}
                (-> hunk
                    (string/replace #"(?m)^." ""))]]])))]])]))


(defn commits "List Github commits for this repo"
  []
  (let [commits (rf/subscribe [:github/filter-by])
        amount (rf/subscribe [:github/commit-count])
        view (r/atom :commits)]
   (fn []
    [:<>
     [:section.github-commits.covering-2
     
     [:h2 [:i.fab.fa-github]" " @amount " commits to "
      [:a {:href (first (string/split (:html_url (first @commits)) #"/commit/"))}
       [:span "this website"]]]
        
     [:div#github-commits-box.github-commits-inner
      [ui/input-text
       :input-type "input.search"
       :placeholder "Search"
       :width "100%"
       :height "2em"
       :path [:form-field [:github :search]]]
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
         {:on-click #(do (rf/dispatch [:github/fetch-commit "tolgraven" "tolgraven" sha])
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
      [:div.github-loading
       [ui/lazy-load-repeatedly
        [:github/fetch-commits-next "tolgraven" "tolgraven"]
        "github-commits-box"]
       [:h3 "Scrolling down should load more..."]
       [:div {:style {:padding "var(--space)"}}
        [ui/loading-spinner true :still]]
       [:button {:style {:margin-top "var(--space-lg)"}
                 :on-click #(rf/dispatch [:github/fetch-commits-next "tolgraven" "tolgraven"])}
        "...or you can click here"]])]]
     [ui/fading :dir "bottom"]])))

