(ns website.modules.github.views
  (:require
    [website.component.registry]
    [website.macros :refer-macros [defc]]
    [website.react :as r]

    [website.modules.github.message :as message]
    [website.modules.github.schema :as schema]
    [website.modules.github.diff :as diff]
    [website.site :as site]
    [clojure.string :as string]
    [website.components.activity :as activity]
    [website.components.ui.controls :as ui-controls]
    [website.components.ui.inputs :as ui-inputs]
    [website.components.ui.layout :as ui-layout]
    [website.components.ui.loading :as ui-loading]
    [website.components.image :as img]
    [website.util :as util :refer [at]]))


(defc <commit> "Show a single commit with highlighted diff etc..."
  {:features [[:appear "zoom-y"]]}
  [sha :- :string close]
  (let [commit @(r/subscribe [:github/commit sha])]
     [:div.github-commit-full
     close
     (for [file (:files commit)
           :let [suffix (-> (:filename file)
                            (string/split #"\.")
                            last)]]
       [:div
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
                    (string/replace #"(?m)^." ""))]]])))]])]))

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
   [:div.info info]
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
  [{:keys [from sha main-view-position view author date ts html_url sha7 commit]}]
  (let [revision [(first @from) (second @from) sha]
        href (when (site/enabled? :neovim) (diff/page-url revision))
        avatar [img/<picture> {:src (:avatar_url author)
                       :alt (str (:login author) " avatar")
                       :class "user-avatar center-content"}]]
    [:div.github-commit.flex
     {:id (str "github-commit-" sha)
      :on-click #(if href
                   (r/dispatch [:github/open-diff revision])
                   (do (r/dispatch [:github/fetch-commit (first @from) (second @from) sha])
                       (reset! main-view-position (.-scrollTop (util/elem-by-id "github-commits-box")))
                       (reset! view sha)))}
     (if href
       [:a {:href href
            :data-document-navigation "true"
            :style {:display "contents"}
            :aria-label (str "View diff for commit " sha7)
            :on-click #(.stopPropagation %)} avatar]
       avatar)
         [:div.github-commit-details
          [:span.github-commit-time date]
          [:span.github-commit-time (util/local-time ts)]
          [:a {:href html_url, :on-click #(.stopPropagation %)}
           [:span.github-commit-sha sha7]]
          [:a.github-commit-permalink
           {:href @(r/subscribe [:href :github-commit {:sha sha}])
            :on-click #(.stopPropagation %)
            :aria-label (str "Permalink to commit " sha7)}
           "Permalink"]

          ;; Parse the provider text here too so restored, previously parsed
          ;; cache entries immediately use the current presentation rules.
          [<commit-message> (message/parse-message (:message commit)) (:message commit)]]]))

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
        view (r/atom :commits)
        main-view-position (r/atom 0)
        ref-fn #(when %
                  (when-let [el (util/elem-by-id "github-commits-box")]
                    (if (= @view :commits)
                      (set! (.-scrollTop el) @main-view-position)
                      (set! (.-scrollTop el) 0))))
        url (r/subscribe [:github/website-url])]
   (fn [spec]
    (let [{:keys [sha path]} @(r/subscribe [:github/route-target])
          search @(r/subscribe [:form-field [:github :search]])
          selected? @(r/subscribe [:github/commit-listed? sha])
          error (when sha @(r/subscribe [:github/commit-error sha]))]
      (r/use-layout-effect
        (fn []
          (when selected?
            (reset! view :commits)
            (r/dispatch [:scroll/component-target "github-commits" path]))
          nil)
        [sha path (boolean selected?)])
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
      [ui-inputs/<input-text> :id "github-commits-filter"
       :input-type "input.search"
       :placeholder "Search commits"
       :width "100%"
       :height "2em"
       :on-change (fn [query & _] (r/dispatch-sync [:github/search query]))
       :attr {:id "github-commits-filter"
              :aria-label "Search commits"
              :maxLength 200}
       :path [:form-field [:github :search]]]
      (when (and sha (not selected?))
        (if error
          [:p {:role "alert"}
           "This commit could not be loaded. "
           [:button {:on-click #(r/dispatch [:github/fetch-commit (first @from) (second @from) sha])}
            "Retry"]]
          [:p {:role "status"} "Loading the requested commit…"]))
      [:div#github-commits-main
       {:ref ref-fn} ; restore scroll when displaying details
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
                            :main-view-position main-view-position
                            :view view)])

          [:div
            [<commit> @view [ui-controls/<close> #(reset! view :commits)]]])

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
