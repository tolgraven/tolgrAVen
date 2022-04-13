(ns tolgraven.github.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.util :as util :refer [at]]))


(defn commit "Show a single commit with highlighted diff etc..."
  [sha]
  (let [commit @(rf/subscribe [:github/commit sha])]
    [:div.github-commit-full
     (for [file  (-> commit :files)]
       [:div.flex
        {:ref #(when % (rf/dispatch [:run-highlighter! %]))} ;screws up live preview :( jumpy
        [:pre.diff
         (-> (:patch file)
             (string/replace #"(?m)(^.).*" "$1")
             (string/replace #"^.*\n" ""))]
        [:pre.clojure
         (-> (:patch file)
             (string/replace #"(?m)^." "")
             (string/replace #"^.*\n" ""))]])]))


(defn commits "List Github commits for this repo"
  []
  (let [commits (rf/subscribe [:github/commits])
        amount (rf/subscribe [:github/commit-count])
        view (r/atom :commits)]
   (fn []
    [:<>
     [:section.github-commits.covering-2
     
     [:h2 [:i.fab.fa-github]" " @amount " commits to "
      [:a {:href (first (string/split (:html_url (first @commits)) #"/commit/"))}
       [:span "this website"]]]
     [:div#github-commits-box.github-commits-inner
      (if (= @view :commits)
           (for [{:keys [commit author html_url sha sha7 message date clock ts] :as item} @commits
                 :let [[info subtitle title] message]]
        ^{:key (str "github-commit-" ts)}
        [:div.github-commit.flex
         {:on-hover #(rf/dispatch [:github/fetch-commit "tolgraven" "tolgraven" sha])
          :on-click #(do (rf/dispatch [:github/fetch-commit "tolgraven" "tolgraven" sha])
                         (reset! view sha))}
         [:img.user-avatar.center-content {:src (:avatar_url author)}]
         [:div.github-commit-details
          [:span.github-commit-time date]
          [:span.github-commit-time clock]
          [:a {:href html_url}
           [:span.github-commit-sha sha7]]

          [:div.github-commit-message
           [:div.info info]
           (if title
             [:div.github-commit-titles
              [:span.subtitle subtitle]
              [:i.fa.fa-solid.fa-arrow-left]
              [:span.title title]]
             (when subtitle
               [:div.github-commit-titles
                [:span.title subtitle]]))]]])

          [:div
            [ui/close #(reset! view :commits)]
            [commit @view]])
      
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

