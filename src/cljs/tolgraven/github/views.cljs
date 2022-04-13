(ns tolgraven.github.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.util :as util :refer [at]]))


(defn commits "List Github commits for this repo"
  []
  (let [commits @(rf/subscribe [:github/commits])
        amount @(rf/subscribe [:github/commit-count])]
    [:<>
     [:section.github-commits.covering-2
     
     [:h2 [:i.fab.fa-github]" " amount " commits to "
      [:a {:href (first (string/split (:html_url (first commits)) #"/commit/"))}
       [:span "this website"]]]
     [:div#github-commits-box.github-commits-inner
      (for [{:keys [commit author html_url] :as item} commits
            :let [ts (get-in commit [:author :date])
                  [date clock] (string/split ts "T") ; all this should be moved to subs yea
                  [info subtitle title :as message]
                  (map string/trim
                       (some-> (:message commit)
                               (string/replace #"\b(\w+):" "$1=====")
                               (string/split #"=====")
                               reverse))]]
        ^{:key (str "github-commit-" ts)}
        [:div.github-commit.flex
         [:img.user-avatar.center-content {:src (:avatar_url author)}]
         [:div.github-commit-details
          [:span.github-commit-time date]
          [:span.github-commit-time clock]
          [:a {:href html_url}
           [:span.github-commit-sha (apply str (take 8 (seq (get-in commit [:tree :sha]))))]]

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
      [:div {:style {:padding "var(--space)"}}
       [ui/loading-spinner true :still]]
      [ui/lazy-load-repeatedly
       [:github/fetch-commits-next "tolgraven" "tolgraven"]
       "github-commits-box"]
      [:div.github-loading [:h3 "Scroll down to load more..."]]]]
     [ui/fading :dir "bottom"]]))

