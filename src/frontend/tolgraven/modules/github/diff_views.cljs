(ns tolgraven.modules.github.diff-views
  (:require [tolgraven.component.registry]
            [tolgraven.macros :as m :refer-macros [defpage defc]]
            [tolgraven.react :as rf]
            [tolgraven.modules.github.diff :as diff]))

(defc <viewer>
  "Revision data stays in re-frame; Neovim owns only its immutable session buffers."
  [revision :- diff/revision-schema]
  (let [key (diff/key-for revision)
        state @(rf/subscribe [:state [:github :diff-view key]])
        content @(rf/subscribe [:content [:github :diff-view key]])
        {:keys [mode selected metadata displayed]} state
        commit (:commit content)
        file (first (filter #(= (:selected displayed) (:filename %)) (:files commit)))
        result (if (= :patch mode) (:patch state) (get-in state [:files selected]))
        retry! #(rf/dispatch [(if (= :error (:status metadata)) :github/diff-open :github/diff-ensure) revision])]
    [:section.github-diff
     [:a {:href "/", :data-document-navigation "true"} "Back to site"]
     [:h1 "Commit " (subs (last revision) 0 7)]
     [:p [:a {:href (str "https://github.com/" (first revision) "/" (second revision) "/commit/" (last revision))}
          "Open on GitHub"] " · Read-only Neovim. Scroll or click either pane; / searches, ]c and [c move between changes."]
     (when commit
       [:<>
        [:pre.github-diff__message (get-in commit [:commit :message])]
        [:p (if-let [parent (get-in commit [:parents 0 :sha])]
              (str "Comparing with " (subs parent 0 7)
                   (when (> (count (:parents commit)) 1) " (first parent of this merge)"))
              "Root commit: the before side is empty.")]
        [:div.github-diff__controls
         [:button {:aria-pressed (= :diff mode), :on-click #(rf/dispatch [:github/diff-mode revision :diff])} "Before / after"]
         [:button {:aria-pressed (= :patch mode), :on-click #(rf/dispatch [:github/diff-mode revision :patch])} "Straight patch"]
         (when (= :diff mode)
           [:label "Changed file "
            [:select {:value selected, :on-change #(rf/dispatch [:github/diff-select revision (.. % -target -value)])}
             (for [{:keys [filename status]} (:files commit)]
               ^{:key filename} [:option {:value filename} (str filename " (" status ")")])]])]
        (when (and (= :diff (:mode displayed)) file)
          [:div.github-diff__revisions
           [:span (str "Before · " (or (:previous_filename file) (:filename file))
                       (when (nil? (:before (diff/sides commit file))) " (absent)"))]
           [:span (str "After · " (:filename file)
                       (when (nil? (:after (diff/sides commit file))) " (absent)"))]])])
     (cond
       (= :error (:status metadata)) [:div {:role "alert"} [:p (:message metadata)] [:button {:on-click retry!} "Retry"]]
       (not= :ready (:status metadata)) [:p {:role "status"} "Loading commit and changed files…"]
       (= :error (:status result)) [:div {:role "alert"} [:p (:message result)] [:button {:on-click retry!} "Retry"]]
       (= :ready (:status result)) nil
       (and (= :diff mode) (empty? (:files commit))) [:p "This commit has no changed files."]
       :else [:p {:role "status"} "Loading revision text…"])
     (when displayed
       (m/<> :neovim/editor {:document (diff/document (:mode displayed) file content), :rows 36}))]))

(defpage <page> []
  (let [{:keys [user repo sha]} (:path-params @(rf/subscribe [:common/route]))]
    [<viewer> [user repo sha]]))
