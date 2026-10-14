(ns website.modules.github.diff-views
  (:require [website.component.registry]
            [website.macros :as m :refer-macros [defpage defc]]
            [website.loader]
            [website.react :as r]
            [website.components.ui.menu :as menu]
            [website.modules.github.diff :as diff]))

(defc <viewer>
  "Revision data stays in re-frame; Neovim owns only its immutable session buffers."
  [revision :- diff/revision-schema]
  :let [*menu-open? (r/atom false)]
  (let [key (diff/key-for revision)
        state @(r/subscribe [:state [:github :diff-view key]])
        content @(r/subscribe [:content [:github :diff-view key]])
        {:keys [mode selected metadata displayed]} state
        commit (:commit content)
        file (first (filter #(= (:selected displayed) (:filename %)) (:files commit)))
        result (if (= :patch mode) (:patch state) (get-in state [:files selected]))
        retry! #(r/dispatch [(if (= :error (:status metadata)) :github/diff-open :github/diff-ensure) revision])]
    (let [menu-content
          [:div.github-diff__menu
           [:a {:href "/", :data-document-navigation "true"} "Back to site"]
           [:strong "Commit " (subs (last revision) 0 7)]
           [:a {:href (str "https://github.com/" (first revision) "/" (second revision) "/commit/" (last revision))}
            "Open on GitHub"]
           (when commit
             [:<>
              [:div.github-diff__controls
               [:button {:aria-pressed (= :diff mode), :on-click #(r/dispatch [:github/diff-mode revision :diff])} "Before / after"]
               [:button {:aria-pressed (= :patch mode), :on-click #(r/dispatch [:github/diff-mode revision :patch])} "Straight patch"]
               (when (= :diff mode)
                 [:label "Changed file"
                  [:select {:value selected, :on-change #(r/dispatch [:github/diff-select revision (.. % -target -value)])}
                   (for [{:keys [filename status]} (:files commit)]
                     ^{:key filename} [:option {:value filename} (str filename " (" status ")")])]])]
              [:details
               [:summary "Commit details & diff help"]
               [:pre.github-diff__message (get-in commit [:commit :message])]
               [:p (if-let [parent (get-in commit [:parents 0 :sha])]
                     (str "Comparing with " (subs parent 0 7)
                          (when (> (count (:parents commit)) 1) " (first parent of this merge)"))
                     "Root commit: the before side is empty.")]
               [:p "Read-only Neovim. Click either pane; / searches, ]c and [c move between changes."]]])]]
      [:section.github-diff
       (when-not displayed
         [menu/<menu> {:open? @*menu-open?
                       :on-open-change #(reset! *menu-open? %)
                       :label "Editor menu"}
          menu-content])
       (cond
         (= :error (:status metadata)) [:div {:role "alert"} [:p (:message metadata)] [:button {:on-click retry!} "Retry"]]
         (not= :ready (:status metadata)) [:p {:role "status"} "Loading commit and changed files…"]
         (= :error (:status result)) [:div {:role "alert"} [:p (:message result)] [:button {:on-click retry!} "Retry"]]
         (= :ready (:status result)) nil
         (and (= :diff mode) (empty? (:files commit))) [:p "This commit has no changed files."]
         :else [:p {:role "status"} "Loading revision text…"])
       (when displayed
         (m/<> :neovim/editor
           {:document (diff/document (:mode displayed) file content)
            :rows 36
            :menu-content menu-content}))])))

(defpage <page> []
  (let [{:keys [user repo sha]} (:path-params @(r/subscribe [:common/route]))]
    [<viewer> [user repo sha]]))
