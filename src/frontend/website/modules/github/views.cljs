(ns website.modules.github.views
  (:require
    [website.component.registry]
    [website.macros :refer-macros [defc]]
    [website.react :as r]

    [website.modules.github.message :as message]
    [website.modules.github.schema :as schema]
    [website.modules.github.diff :as diff]
    [website.modules.github.patch :as patch]
    [website.modules.highlight.code-block :as code-block]
    [website.modules.markdown.interface :as markdown]
    [website.site :as site]
    [clojure.string :as string]
    [website.components.activity :as activity]
    [website.components.ui.inputs :as ui-inputs]
    [website.components.ui.layout :as ui-layout]
    [website.components.ui.loading :as ui-loading]
    [website.components.image :as img]
    [website.util :as util :refer [at]]))


(defn- source-gutter [{:keys [old new kind]}]
  [:span.github-patch__gutter {:aria-hidden true}
   [:span.github-patch__number old]
   [:span.github-patch__number new]
   [:span.github-patch__sign (case kind :addition "+" :deletion "-" " ")]])

(defn- number-width [hunks]
  (let [positions (mapcat (juxt :old :new) (mapcat :rows hunks))
        digits (apply max 3 (map #(count (str %)) (remove nil? positions)))]
    (str (+ digits 2) "ch")))

(defc ^:private <hunk>
  [hunk :- patch/hunk-schema language]
  (let [rows (:rows hunk)
        source (string/join "\n" (map :text rows))
        options {:language language
                 :line-numbers? false
                 :line-prefix #(source-gutter (nth rows (dec %)))
                 :line-props (fn [number]
                               (let [{:keys [kind old new]} (nth rows (dec number))]
                                 {:class (str "github-patch__line github-patch__line--" (name kind))
                                  :data-old-line old
                                  :data-new-line new}))}]
    [:div.github-patch__hunk
     [:pre.github-patch__header (:header hunk)]
     [code-block/<formatter> source options (constantly nil)]]))

(defc <commit> "A unified diff with absolute old/new source line numbers."
  {:features [[:appear "zoom-y"]]}
  [sha :- :string close]
  (let [commit @(r/subscribe [:github/commit sha])
        error @(r/subscribe [:github/commit-error sha])
        pending? @(r/subscribe [:github/commit-pending? sha])
        [user repo] @(r/subscribe [:github/get-from])
        revision [user repo (:sha commit)]]
    [:div.github-commit-full
     close
     (cond
       error [:div {:role "alert"}
              [:p "The commit could not be loaded."]
              [:button {:on-click #(r/dispatch [:github/fetch-commit user repo sha])} "Retry"]]
       (nil? commit) [:p {:role "status"} "Loading commit diff…"]
       :else
       [:<>
        [:div.github-patch__commit
         [:h3 (first (string/split-lines (get-in commit [:commit :message] "")))]
         [:div.github-patch__message
          [markdown/<parse-markdown-components>
           (string/join "\n" (rest (string/split-lines (get-in commit [:commit :message] ""))))
           {:allow-raw? false
            :allow-components? false
            :allow-images? false}]]
         [:dl.github-patch__metadata
          [:dt "Commit"] [:dd [:code (:sha commit)]]
          [:dt "Author"] [:dd (or (get-in commit [:commit :author :name]) (get-in commit [:author :login]) "Unknown")]
          [:dt "Authored"] [:dd [:time {:date-time (get-in commit [:commit :author :date])}
                                 (get-in commit [:commit :author :date])]]
          [:dt "Committed"] [:dd [:time {:date-time (get-in commit [:commit :committer :date])}
                                  (get-in commit [:commit :committer :date])]]
          (when (seq (:parents commit))
            [:<> [:dt "Parents"]
             [:dd (for [{:keys [sha html_url]} (:parents commit)]
                    ^{:key sha} [:a {:href html_url} [:code (subs sha 0 (min 7 (count sha)))]])]])]
         [:p (util/pluralize (count (:files commit)) " changed file")
          (when-let [stats (:stats commit)]
            [:<> " · " [:span.github-patch__additions (util/pluralize (:additions stats) " addition")]
             [:span.github-patch__deletions (util/pluralize (:deletions stats) " deletion")]])]
         [:a {:href (:html_url commit)} "View commit on GitHub"]
         (when (site/enabled? :neovim)
           [:a {:href (diff/page-url revision), :data-document-navigation "true"}
            "Open full before / after workspace"])]
        (when pending? [:p {:role "status"} "Loading remaining changed files…"])
        (when (empty? (:files commit)) [:p "This commit has no changed files."])
        (for [{:keys [filename previous_filename status additions deletions] :as file} (:files commit)
              :let [hunks (patch/hunks (:patch file))]]
          ^{:key filename}
          [:div.github-patch {:style {"--github-patch-number-width" (number-width hunks)}}
           [:div.github-patch__file
            [:h4 [:code filename]]
            [:span.github-patch__status status]
            (when previous_filename [:p "Renamed from " [:code previous_filename]])
            [:span.github-patch__additions (util/pluralize additions " addition")]
            [:span.github-patch__deletions (util/pluralize deletions " deletion")]]
           (if (seq hunks)
             [:div.github-patch__scroll {:tab-index 0, :aria-label (str "Diff for " filename)}
              (for [[index hunk] (map-indexed vector hunks)]
                ^{:key index} [<hunk> hunk (patch/language filename)])]
             [:p.github-patch__unavailable
              "No text patch is available for this file. "
              [:a {:href (:html_url commit)} "View the change on GitHub"]])])])]))

(defc <loading> "Lazy load more on scroll to bottom, with a button as fallback for the poors"
  {:features [[:on-seen (fn [user repo]
                         {:event [:github/fetch-commits-next user repo]
                          :once? false :delay-ms 500})]]}
  [user :- :string repo :- :string]
  (fn [user repo]
   [:div.github-loading
   [:h2 "Loaded " (count @(r/subscribe [:github/commits]))]
   [:h3 "Scrolling down should load more..."]
   [:div {:style {:padding "var(--space)"}}
    [ui-loading/<loading-spinner> true :still]]
   [:button {:style {:margin-top "var(--space-lg)"}
             :on-click #(do (r/dispatch [:github/fetch-commits-next user repo]))}
    "...or you can click here"]]))

(defc <commit-message>
  {:args-schema [:cat schema/parsed-message [:? :?string]]}
  [[info subtitle title] & [full-message]]
  [:div.github-commit-message {:title (or full-message info)}
   [:div.info (first (string/split-lines info))]
   (when subtitle
     [:div.github-commit-titles
      [:span {:class (if title "subtitle" "title")
              :style {:cursor "pointer"}
              :on-click (fn [e]
                          (.stopPropagation e)
                          (.preventDefault e)
                          (r/dispatch [:github/search subtitle]))}
       subtitle]
      (when title
        [:<>
         [:i.fa.fa-solid.fa-arrow-left]
         [:span.title
          {:style {:cursor "pointer"}
           :on-click (fn [e]
                       (.stopPropagation e)
                       (.preventDefault e)
                       (r/dispatch [:github/search title]))}
          title]])])])

(defc ^:private <commit-row>
  {:features [[:appear "slide-in slow"]]}
  [{:keys [from sha main-view-position author date ts html_url sha7 commit]}]
  (let [open! (fn []
                (reset! main-view-position (.-scrollTop (util/elem-by-id "github-commits-box")))
                (r/dispatch [:github/select-commit sha]))
        avatar [img/<picture> {:src (:avatar_url author)
                       :alt (str (:login author) " avatar")
                       :class "user-avatar center-content"}]]
    [:div.github-commit.flex
     {:id (str "github-commit-" sha)
      :on-click (fn [event]
                  ;; Native links must reach Reitit's document handler. They do
                  ;; not also trigger the row's local detail selection.
                  (when-not (.closest (.-target event) "a, button") (open!)))}
     avatar
         [:div.github-commit-details
          [:span.github-commit-time date]
          [:span.github-commit-time (util/local-time ts)]
          [:a {:href html_url, :on-click #(.stopPropagation %)}
           [:span.github-commit-sha sha7]]
          [:a.github-commit-permalink
           {:href @(r/subscribe [:href :github-commit {:sha sha}])
            :aria-label (str "Permalink to commit " sha7)}
           "Permalink"]

          [:button.github-commit-open
           {:type "button"
            :on-click (fn [event] (.stopPropagation event) (open!))
            :aria-label (str "View diff for commit " sha7)}
           "View diff"]
          ;; Parse the provider text here too so restored, previously parsed
          ;; cache entries immediately use the current presentation rules.
          [<commit-message> (message/parse-subject (:message commit)) (:message commit)]]]))

(r/reg-sub :github/deployment (fn [db _] (get-in db [:module :github :deployment])))
(r/reg-sub :github/activity
  (fn [db [_ user]] (get-in db [:module :github :activity user])))

(defc <deployment>
  {:depends [{:source :url
              :url "/api/deployment"
              :into [:module :github :deployment]}]
   :loading [:p.github-deployment "Loading deployment details…"]}
  []
  (let [{:keys [commit deployed-at commit-url]} @(r/subscribe [:github/deployment])]
    [:div.github-deployment
     [:span "Latest deploy "]
     (if deployed-at
       [:time {:date-time deployed-at, :title deployed-at} (string/replace deployed-at "T" " ")]
       [:span "Timestamp unavailable"])
     (when commit
       [(if commit-url :a :span) {:href commit-url, :title commit} [:code (subs commit 0 7)]])]))

(defn activity-dependencies [user]
  [{:source :url
    :url (str "/api/github/activity?user=" (js/encodeURIComponent user))
    :into [:module :github :activity user]}])

(defn day-dependencies [user date]
  [{:source :url
    :url (str "/api/github/activity/day?user=" (js/encodeURIComponent user) "&date=" date)
    :into [:module :github :days user date]}])

(r/reg-sub :github/day (fn [db [_ user date]] (get-in db [:module :github :days user date])))

(defc <day-details>
  {:depends day-dependencies
   :data-error [:p "Breakdown temporarily unavailable."]
   :loading [:p "Loading breakdown…"]}
  [user date]
  (let [{:keys [types]} @(r/subscribe [:github/day user date])]
    [:ul
     (for [[kind label] [[:commits "Commits"] [:issues "Issues"]
                        [:pull-requests "Pull requests"] [:reviews "Reviews"]
                        [:repositories "Repositories"] [:restricted "Private contributions"]]
           :let [amount (get types kind 0)]
           :when (pos? amount)]
       ^{:key kind} [:li [:span label] [:strong amount]])]))

(defc <activity>
  {:depends activity-dependencies
   :loading [:section.github-activity.covering-2 [:h2 "GitHub activity"] [:p "Loading contribution calendar…"]]}
  [user :- :string]
  (let [{:keys [days]} @(r/subscribe [:github/activity user])]
    [:section.github-activity.covering-2
     [:h2 [:a {:href (str "https://github.com/" user)} (str user " on GitHub")]]
     [activity/<calendar> days [<day-details> user]]]))

(defc <repository> "List Github commits for the configured public repository"
  {:features [:lifecycle]}
  [spec]
  (let [from (r/subscribe [:github/get-from])
        commits (r/subscribe [:github/commits])
        amount (r/subscribe [:github/commit-count])
        complete? (r/subscribe [:github/archive-complete?])
        more? (r/subscribe [:github/more?])
        view (r/subscribe [:github/commit-view])
        main-view-position (r/atom 0)
        url (r/subscribe [:github/website-url])]
   (fn [spec]
    (let [{:keys [sha path]} @(r/subscribe [:github/route-target])
          active-view @view]
      (r/use-layout-effect
        (fn []
          (when (and sha (= @view :commits))
            (when-let [element (util/elem-by-id "github-commits-box")]
              (reset! main-view-position (.-scrollTop element))))
          (r/dispatch-sync (if sha [:github/select-commit sha] [:github/close-commit]))
          (when sha
            (r/dispatch [:scroll/component-target "github-commits" path]))
          nil)
        [sha path])
      ;; This mounted scrollbox switches content without replacing its native node.
      (r/use-layout-effect
        (fn []
          (when-let [element (util/elem-by-id "github-commits-box")]
            (set! (.-scrollTop element) (if (= active-view :commits) @main-view-position 0)))
          nil)
        [active-view])
    [:<>
     [:section#github-commits.github-commits.covering-2

     [<deployment>]
     [:h2 [:i.fab.fa-github]" " @amount " commits to "
      [:span
       [:a {:title "The repo" :href @url}
        (str (second @from))]]]
     [:p.github-repository-owner (str "by " (first @from))]
     (when-not @complete?
       [:div.github-archive-status
        [:p "Indexing repository history… search includes commits archived so far."]
        [:button {:on-click #(r/dispatch [:github/refresh])} "Refresh indexed history"]])

     [:div#github-commits-box.github-commits-inner
      (when (= active-view :commits)
       [ui-inputs/<input-text> :id "github-commits-filter"
       :input-type "input.search"
       :placeholder "Search commits"
       :width "100%"
       :height "2em"
       :on-change (fn [query & _] (r/dispatch-sync [:github/search query]))
       :attr {:id "github-commits-filter"
              :aria-label "Search commits"
              :maxLength 200}
       :path [:form-field [:github :search]]])
      [:div#github-commits-main
       (when (= @view :commits)
        [:div {:style {:text-align "center"
                       :padding "1em"
                       :font-size "80%"
                       :color "var(--fg-5)"}}
          "Press a commit for details and diff"])
      (if (= @view :commits)
       (for [{:keys [sha] :as item} @commits]
            ^{:key (str "github-commit-" sha)}
        [<commit-row> (assoc item
                            :from from
                            :main-view-position main-view-position)])

          [:div
            [<commit> @view
             [:button.github-patch__back
              {:type "button", :on-click #(r/dispatch [:github/close-commit])} "← Back to commits"]]])

     (when (and (= @view :commits) @more?)
      [<loading> (first @from) (second @from)])]]]
     [ui-layout/<fading> :dir "bottom"]]))))

(defc <commits> []
  (let [[user repo] @(r/subscribe [:github/get-from])
        configuration @(r/subscribe [:github/configuration-result])]
    (cond
      (and (seq user) (seq repo))
      [:<>
       ^{:key (str user "/" repo)} [<repository> {:init (fn [_] (r/dispatch [:github/init]))}]
       ^{:key user} [<activity> user]]

      ;; SSR has not acquired the managed URL binding. Only a completed
      ;; settings response can establish that the repository is unconfigured.
      (nil? configuration)
      [:section#github-commits.github-commits.covering-2
       {:aria-busy true}
       [:h2 "GitHub"]
       [:p "Loading repository settings…"]]

      :else
      [:section#github-commits.github-commits.covering-2 [:h2 "GitHub"] [:p "Configure a GitHub user and repository in the server settings."]])))
