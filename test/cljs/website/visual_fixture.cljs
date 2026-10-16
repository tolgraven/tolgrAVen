(ns website.visual-fixture
  "Fixed public content and native components for pixel/geometry comparisons."
  (:require [clojure.string :as string]
            [website.react :as r]
            [website.editor-visual :as editor]
            [website.component.restore :as restore]
            [website.component.data :as data]
            [website.supabase.runtime :as supabase]
            [website.modules.contact.events]
            [website.modules.user.events]
            [website.modules.docs.views :as docs]
            [website.modules.docs.events]
            [website.modules.docs.subs]
            [website.modules.instagram.views :as instagram]
            [website.modules.instagram.subs]
            [website.strava-visual-fixture :as strava-fixture]
            [website.post-editor-visual-fixture :as post-editor-fixture]
            [website.components.ui.overlays :as overlays]
            [website.modules.github.views :as github]
            [website.modules.github.model :as github-model]
            [website.modules.github.events]
            [website.modules.github.subs]
            [website.modules.cv.views :as cv]
            [website.modules.blog.views :as blog]
            [website.modules.home.views :as home-view]
            [website.page-layout :as page-layout]
            [website.modules.blog.comment-views :as comments]
            [website.modules.blog.events]
            [website.modules.blog.subs]
            [website.modules.blog-preview.views :as preview]
            [website.modules.search.views :as search]
            [website.modules.search.model :as search-model]
            [website.validation :as validation]
            [website.components.shell :as shell]
            [website.components.media :as media]
            [website.modules.home.sections :as home]
            [website.loader :as loader]
            [website.navigation.routes :as routes]
            [reitit.frontend.easy :as rfe]
            [reitit.core :as reitit])
  (:require-macros [website.macros :as m]))

(def github-commits
  (mapv (fn [index message]
          (let [sha (apply str (repeat 40 (nth ["a" "b" "c" "d"] index)))]
            {:sha sha
             :html_url (str "https://github.com/visual-user/visual-repo/commit/" sha)
             :author {:login "visual-user"
                      :avatar_url "/img/tolgrav-square.png"}
             :commit {:message message
                      :author {:name "Visual contributor"
                               :date (str "2026-10-" (- 14 index) "T12:30:00Z")}}}))
        (range 4)
        ["fix(github): keep commit tags, timestamps and links aligned"
         "scss: theme: preserve the original spacing and native colors"
         "Keep URLs intact: https://example.invalid/a/long/path?with=parameters"
         "feat(api): show a multiline commit message\n\nKeep the provider body below the subject."]))

(def github-days
  (mapv (fn [index]
          (let [date (subs (.toISOString (js/Date. (+ (.UTC js/Date 2025 9 10) (* index 86400000)))) 0 10)
                amount (if (= date "2026-10-12") 13 (mod index 5))]
            {:date date
             :count amount
             :level (min 4 amount)}))
        (range 371)))

(defn github-transport! []
  ;; Only provider I/O is fixed. Managed dependency installation, HTTP result
  ;; cleanup, initialization, query generations, normalization and subscriptions
  ;; all remain native. Each visual case has its own disposable document realm.
  (data/register-source! :url
    {:load! (fn [{:keys [url]}]
              (js/Promise.resolve
                (case url
                  "/api/github/settings" {:settings {:user "visual-user", :repo "visual-repo"}}
                  "/api/deployment" {:commit (:sha (first github-commits))
                                     :deployed-at "2026-10-15T12:00:00Z"
                                     :commit-url (:html_url (first github-commits))}
                  "/api/github/activity?user=visual-user" {:days github-days}
                  "/api/github/activity/day?user=visual-user&date=2026-10-12"
                  {:date "2026-10-12"
                   :types {:commits 7
                           :issues 2
                           :pull-requests 1
                           :reviews 2
                           :repositories 1
                           :restricted 0}}
                  (throw (js/Error. (str "Unexpected GitHub fixture resource: " url))))))})
  (r/reg-fx :http-xhrio
    (fn [{:keys [method uri url-params on-success]}]
      (when-not (= :get method)
        (throw (js/Error. "GitHub visual fixtures only provide reads")))
      (let [response (if (= uri "/api/github/commits")
                       (let [rows (filterv #(github-model/matches? % (:q url-params)) github-commits)]
                         {:commits rows
                          :total (count rows)
                          :complete true
                          :next-cursor nil})
                       (or (some #(when (= uri (str "/api/github/commits/" (subs (:sha %) 0 7))) %)
                                 github-commits)
                           (throw (js/Error. (str "Unexpected GitHub fixture request: " uri)))))]
        (when-not (= {:user "visual-user", :repo "visual-repo"}
                     (select-keys url-params [:user :repo]))
          (throw (js/Error. "GitHub fixture requested a different repository")))
        (r/dispatch (conj on-success response))))))

(def instagram-posts
  (into {}
    (map-indexed
      (fn [index image]
        (let [id (str "visual-instagram-" index)]
          [id {:id id
               :caption (str "A native gallery photograph " (inc index) ". Keep its caption and image framing.")
               :timestamp (str "2026-10-" (+ 10 index) "T12:00:00Z")
               :media_url image}]))
      ["/img/tolgrav-square.png" "/img/Leg-tasteful-small.jpg" "/img/tolgrav.png"
       "/img/Leg-tasteful-small.jpg" "/img/tolgrav.png" "/img/tolgrav-square.png"])))

(defn public-read-transport! []
  ;; Only read I/O is fixed. Native dependency installation and module-owned
  ;; subscriptions still determine when Docs and Instagram render their views.
  (data/register-source! :url
    {:load! (fn [{:keys [url]}]
              (if (= url "/api/integrations/instagram")
                (js/Promise.resolve {:posts instagram-posts})
                (if-let [[_ page] (re-matches #"/api/doc\?path=(index|docs)" url)]
                  (-> (js/fetch (str "/fixtures/docs/" page ".html"))
                      (.then (fn [response]
                               (when-not (.-ok response)
                                 (throw (js/Error. (str "Missing generated Codox fixture: " page))))
                               (.text response)))
                      ;; Match the backend's trusted generated-body contract.
                      (.then #(string/replace %
                                (js/RegExp. "^[\\s\\S]*<body[^>]*>([\\s\\S]*)</body>[\\s\\S]*$")
                                "$1")))
                  (js/Promise.reject (js/Error. (str "Unexpected public fixture resource: " url))))))}))

(def entry
  {:id "visual-entry"
   :kind "regular"
   :from 2018
   :to 2022
   :what "Native timeline entry"
   :position "Developer and sound engineer"
   :where "Stockholm"
   :how ["Build software and run live productions."
         "Keep the original spacing, dates, icons and interactions."]
   :color "#6c5781"})

(def comment-row
  {:id "visual-comment"
   :user "visual-writer"
   :parent-post 42
   :parent-comment nil
   :path [42]
   :reply-count 0
   :title "A native comment with code"
   :text (str "The same fixed comment is rendered by the production component.\n\n```clojure\n"
              (apply str (repeat 32 "(map inc [1 2 3])\n"))
              "```\n\nText after the code block.")
   :ts 1609459200000
   :score 3})

(def writer
  {:id "visual-writer"
   :name "Visual writer"
   :avatar "/img/tolgrav-square.png"
   :bg-color "#635177"
   :comment-count 12
   :karma 3
   :roles []})

(def post-row
  {:id 42
   :user "visual-writer"
   :title "Native post header"
   :tags "clojure layout"
   :text "Fixed public post content with its actual author avatar."
   :ts 1609459200000})

(def replies
  {"visual-comment"
   {"first-reply" (assoc comment-row :id "first-reply" :parent-comment "visual-comment"
                                   :path [42 "visual-comment"] :reply-count 1
                                   :title "A native reply" :text "First reply with one child.")
    "second-reply" (assoc comment-row :id "second-reply" :parent-comment "visual-comment"
                                    :path [42 "visual-comment"] :ts 1609459202000
                                    :title "Another native reply" :text "A sibling in the same thread.")}
   "first-reply"
   {"grandchild" (assoc comment-row :id "grandchild" :parent-comment "first-reply"
                                  :path [42 "visual-comment" "first-reply"]
                                  :title "A nested reply" :text "Rendered through the native reply row.")}})

(def hit
  {:document {:id "post-42"
              :kind "blog-post"
              :title "A marked search result"
              :text "Native text around the highlighted hit."
              :href "/blog/post/42"
              :ts 1609459200000
              :user "visual-writer"
              :author "Visual writer"
              :tags "clojure layout"}
   :preview :blog-preview/post
   :title [{:text "A ", :marked? false} {:text "marked", :marked? true} {:text " search result", :marked? false}]
   :excerpt [{:text "Native text around the ", :marked? false}
             {:text "highlighted", :marked? true}
             {:text " hit, including literal <script> text.", :marked? false}]})

(def search-response
  {:query "highlighted"
   :found 3
   :page 1
   :suggestions ["highlighted content"]
   :hits [hit
          (-> hit
              (assoc :preview :blog-preview/comment)
              (assoc-in [:document :id] "comment-113")
              (assoc-in [:document :kind] "blog-comment")
              (assoc-in [:document :href] "/blog/post/42/comment/113"))
          (assoc-in hit [:document :id] "post-43")]})

(def extended-search-response
  (let [commit (first github-commits)]
    (-> search-response
        (assoc :found 5)
        (update :hits into
          [(assoc hit
                  :document {:id (str "github-" (:sha commit))
                             :kind "github-commit"
                             :title "GitHub highlighted change"
                             :text "Native text around the highlighted commit."
                             :href (str "/github/commits/" (:sha commit))
                             :repository "visual-user/visual-repo"
                             :author "Visual contributor"}
                  :preview :github-preview/commit
                  :title (search-model/marked-segments "GitHub <mark>highlighted</mark> change"))
           (assoc hit
                  :document {:id "site-section-services"
                             :kind "site-section"
                             :title "Site highlighted section"
                             :text "Native text around the highlighted site section."
                             :href "/section/services"}
                  :preview :home-preview/section
                  :title (search-model/marked-segments "Site <mark>highlighted</mark> section"))]))))

(m/defpage <fixture-page>
  "Use the native page-root container: direct grid children stretch to main's minimum height."
  [content]
  content)

(defn <main-fixture> [content]
  [:main#main.main-content.perspective-top
   [<fixture-page> content]])

(m/defpage <instagram-page>
  {:depends [{:source :url
              :url "/api/integrations/instagram"
              :into [:content :instagram]}]}
  []
  [instagram/<instagram>])

(defn <instagram-fixture> []
  [:<>
   ;; The native fullscreen owner is a page sibling, outside main's perspective.
   [overlays/<zoom-to-modal> :fullscreen]
   [:main#main.main-content.perspective-top [<instagram-page>]]])

(defn <docs-fixture> []
  ;; Docs already owns a defpage container; do not nest another page surface.
  [:main#main.main-content.perspective-top [docs/<page>]])

(defn <home-fixture> [component]
  (let [content' #(editor/section % false)]
    [<main-fixture>
     (case component
       "home-intro" [home/<intro> (content' :intro)]
       "home-story" [home/<story> (content' :story)]
       "home-image-media"
       [:<>
        [media/<moneyshot> (content' :moneyshot)]
        ;; The seed's third interlude is the native still-image branch. Video
        ;; playback needs its own explicit frame/readiness contract.
        [media/<interlude> (content' :interlude) 2]
        [media/<gallery> (content' :gallery)]])]))

(defn <search-fixture> []
  ;; The site's expandable Search panel is a main sibling, not a padded page
  ;; section. Its native input, suggestions, status and scroll clipping matter.
  [:<>
   [search/<ui>]
   [<main-fixture> [:section [:p "Fixed page content behind Search."]]]])

(defn ^:export shell-state! [state]
  ;; Visual actions exercise the real events and CSS effects. The fixture does
  ;; not install the full boot listener host or assign shell classes/styles.
  (r/dispatch-sync [:scroll/past-top true])
  (r/dispatch-sync [:scroll/at-bottom false])
  (case state
    "minimized" (r/dispatch-sync [:hide-header-footer true true])
    "restored" (r/dispatch-sync [:hide-header-footer false false])
    "bottom" (do (r/dispatch-sync [:hide-header-footer false false])
                 (r/dispatch-sync [:scroll/at-bottom true]))
    (throw (js/Error. (str "Unknown shell fixture state: " state)))))

(defonce *composer-requests (atom []))

(defn ^:export composer-text! [text]
  (r/dispatch-sync [:form-field [:write-comment [42 "visual-comment"] :text] text]))

(defn ^:export composer-state []
  ;; Observe the native local form/editor owner for teardown checks. No second
  ;; application state or replacement view is installed by these fixture reads.
  (clj->js
    {:text @(r/subscribe [:form-field [:write-comment [42 "visual-comment"] :text]])
     :post-text @(r/subscribe [:form-field [:post-blog :text]])
     :editors (count (filter #(= :comment (:kind %))
                            (vals @(r/subscribe [:get :state :blog-editor :editors]))))
     :uploads (count (filter #(= :comment (:kind %))
                            (vals @(r/subscribe [:blog/state [:image-uploads]]))))
     :requests (count @*composer-requests)}))

(defn ^:export panel-state! [state]
  ;; Exercise native state/presence events without following query links or
  ;; submitting credentials. In particular, the login Register button is Auth.
  (case state
    "closed" (do (r/dispatch-sync [:user/close-ui])
                 (r/dispatch-sync [:state [:settings :panel-open] false])
                 (r/dispatch-sync [:contact/close]))
    "login" (r/dispatch-sync [:user/open-ui :login])
    "register" (do (r/dispatch-sync [:user/open-ui :login])
                   (r/dispatch-sync [:user/active-section :register]))
    "settings" (r/dispatch-sync [:state [:settings :panel-open] true])
    "contact" (r/dispatch-sync [:contact/open])
    (throw (js/Error. (str "Unknown panel fixture state: " state)))))

(defn <shell-fixture> [component]
  (let [content' #(editor/section % false)
        scroll? (= "shell-scroll" component)
        panels? (= "shell-panels" component)]
    [:<>
     (when (#{"header" "shell" "shell-scroll" "shell-panels"} component)
       [shell/<header> (content' :header)])
     ;; Match components/page: User and conditional Settings are main siblings;
     ;; the Contact presence host follows both footer copies. No extra wrapper.
     (when panels?
       (let [open? @(r/subscribe [:user/ui-open?])]
         (m/<> {:module :user
                :defer? (not open?)
                :load-on (if open? :immediate :event)})))
     (when (and panels? @(r/subscribe [:state [:settings :panel-open]]))
       (m/<> {:module :settings, :load-on :immediate}))
     [<main-fixture>
      [:section
       [:h1 "Shared shell"]
       [:p "Fixed page content."]
       (when scroll?
         ;; Native paragraph layout keeps the full footer beyond its real
         ;; observation margin until the case navigates to the page bottom.
         (for [index (range 100)]
           ^{:key index}
           [:p "Fixed page context for minimized, restored and bottom-of-page shell states."]))]]
     ;; Production footers are siblings of main. The full footer already owns
     ;; post-footer, including its font size and natural flex-row height.
     (when (#{"footer" "post-footer" "shell" "shell-scroll" "shell-panels"} component)
       [shell/<footer-full> (content' :footer)])
     (when (#{"shell" "shell-scroll" "shell-panels"} component)
       [shell/<footer> (content' :footer)])
     (when panels?
       (let [open? @(r/subscribe [:state [:contact-form :show?]])]
         (m/<> {:module :contact
                :view :popup
                :defer? (not open?)
                :load-on (if open? :immediate :event)
                :<loading> (if open? [:span {:role "status"} "Opening contact form…"] [:<>])})))]))

(defn <box-fixture> [container?]
  (let [items (mapcat :things (get-in @(r/subscribe [:content/view [:cv]]) [:cv :timeline]))
        current-year (.getUTCFullYear (js/Date.))
        first-year (apply min (map :from items))
        last-year (apply max (map #(if (number? (:to %)) (:to %) (+ 3 current-year)) items))
        span (- last-year first-year)
        position (str (* 95 (/ (- (:from entry) first-year) span)) "%")
        size (str (* 92 (/ (- (:to entry) (:from entry)) span)) "%")]
    [<main-fixture>
     [:section.cv.nopadding.noborder
      [:div.cv-boxes
       ;; Match the native timeline column in cv/<cv>. Absolutely positioned boxes
       ;; cannot establish their parent's width by themselves. This fixed single-row
       ;; column has native height-ratio 1; dates use the seed's actual shared range.
       [:div.cv-items.cv-work {:style {:width "200em", :height "9em"}}
        [cv/<box> (cond-> entry container?
                    (assoc :kind "container" :what "Festival day"
                           :children [(assoc entry :id "performance" :what "Main stage")]))
         position size 0 nil
         {:column 0, :category "work", :parents [], :parent-keys []}]]]]]))

(defn <post-fixture> [component]
  [<main-fixture>
   [blog/<blog-container>
    {:tag-cloud? false
     :section [blog/<post-content>
               {:post (cond-> post-row (= "comments-edge" component)
                        (assoc :text (apply str (repeat 14 "Context before the comment.\n\n"))))
                :features [[:appear false]]}]}]])

(defn <standalone-fixture> []
  [<main-fixture>
   [blog/<blog-container>
    {:tag-cloud? false
     :section [comments/<standalone-comment> "visual-comment"]}]])

(m/defpage <layout-surface> [vertical?]
  [home-view/<layout>
    (let [page (page-layout/find-page page-layout/defaults "experiments")
          page' (update page :sections (fn [sections] (filterv #(#{"story" "page-index"} (:component %)) sections)))
          [story index] (:sections page')]
      (if vertical?
        (-> page'
            (dissoc :columns)
            (assoc :sections [index
                              (update-in story [:content :text] #(str % "\n\n" % "\n\n" %))
                              (assoc story :identity "second-story", :label "Second article")]))
        page'))])

(defn <layout-fixture> [vertical?]
  [:main#main.main-content [<layout-surface> vertical?]])


(defn ^:export init! []
  (let [params (js/URLSearchParams. (.-search js/location))
        component (.get params "component")
        composer? (and (= component "comments-auth")
                       (contains? #{"rich" "plain"} (.get params "composer")))]
    (if-not (#{"page-layout" "page-layout-vertical"
               "cv-regular" "cv-container" "comments" "comments-auth" "comments-reader" "comments-roots" "comments-edge"
               "thread" "post" "standalone-comments" "search-mixed" "search-extensions"
               "shell" "shell-scroll" "shell-panels" "header" "footer" "post-footer" "services" "cv"
               "home-intro" "home-story" "home-image-media" "github"
               "docs-index" "docs-topic" "instagram" "strava" "post-editor"} component)
      (editor/init! false)
      (-> (js/fetch "/fixtures/editor-seed.json")
          (.then #(.json %))
          ;; Settled layout cases use one deterministic native font state. The
          ;; dedicated font suite still exercises cold fallback and crossfades.
          (.then (fn [seed]
                   (-> (.load (.-fonts js/document) "1em 'Fira Code'" "MWiw0123456789")
                       (.then (fn [faces]
                                (when (zero? (.-length faces))
                                  (throw (js/Error. "Fixture monospace font did not load")))
                                seed)))))
          (.then
            (fn [seed]
              (rfe/start! routes/router (fn [_ _]) {:use-fragment false})
              (r/dispatch-sync [:editor-visual/install (js->clj seed :keywordize-keys true) false])
              (when composer?
                ;; Only these cases deny provider I/O. The real comment owner
                ;; supplies :library? false and never needs a post draft or Auth.
                (r/dispatch-sync [:state [:supabase-init] :ready])
                (r/dispatch-sync [:form-field [:post-blog :text] "Retained post draft"])
                (set! supabase/authenticated-request!
                  (fn [method uri & _]
                    (swap! *composer-requests conj {:method method, :uri uri})
                    (js/Promise.reject (js/Error. "Composer visual fixtures do not perform provider I/O"))))
                (r/reg-fx :http-xhrio
                  (fn [request]
                    (swap! *composer-requests conj (select-keys request [:method :uri]))
                    (throw (js/Error. "Composer visual fixtures do not perform HTTP or writes")))))
              (when (= "shell-panels" component)
                ;; Public presentation flags only. The already-ready store path
                ;; prevents login-panel intent from starting settings/SDK boot.
                (r/dispatch-sync [:state [:supabase-init] :ready])
                (r/dispatch-sync [:set [:options :supabase :providers]
                                  {:google true, :github true, :facebook true}])
                (panel-state! "closed")
                (r/reg-fx :http-xhrio
                  (fn [_] (throw (js/Error. "Panel visual fixtures do not perform HTTP or writes")))))
              (when (#{"comments" "comments-auth" "comments-reader" "comments-roots" "comments-edge"
                       "thread" "post" "search-mixed"} component)
                (r/dispatch-sync [:set [:common/route]
                                  (assoc (reitit/match-by-path routes/router "/blog/post/42")
                                         :parameters {:path {:permalink "42"}, :query {}})]))
              (when (#{"docs-index" "docs-topic"} component)
                (r/dispatch-sync [:set [:common/route]
                                  (reitit/match-by-path routes/router
                                    (if (= component "docs-index") "/docs" "/docs/codox/docs"))]))
              (when (= "standalone-comments" component)
                (r/dispatch-sync [:set [:common/route]
                                  (assoc (reitit/match-by-path routes/router "/blog/comment/visual-comment")
                                         :parameters {:path {:comment-id "visual-comment"}, :query {}})]))
              (when (#{"page-layout" "page-layout-vertical"} component)
                (r/dispatch-sync [:set [:common/route]
                                  (assoc (reitit/match-by-path routes/router "/blog/post/42")
                                         :parameters {:path {:permalink "42"}, :query {}}
                                         :path (.-pathname js/location)
                                         :query-params {:component component})]))
              ;; Only provider boundaries are fixed. Native comment markup,
              ;; actions, clip measurement and expansion events are unchanged.
              (r/reg-sub :user/user
                         (fn [_ _]
                           (cond-> writer (= "post" component)
                             (assoc :avatar "/img/Leg-tasteful-small.jpg"))))
              (r/reg-sub :user/active-user
                         (fn [_ _]
                           (case component
                             ("comments-auth" "comments-edge") writer
                             "post-editor" editor/profile
                             "comments-reader" (assoc writer :id "visual-reader" :name "Visual reader")
                             nil)))
              (r/reg-sub :blog/vote (fn [_ _] nil))
              (r/reg-sub :comments/visible-thread
                         (fn [_ [_ _ parent-id]]
                           (if (= "thread" component) (get replies parent-id {}) {})))
              (r/reg-sub :comments/root-page
                         (fn [_ _]
                           {:records (if (#{"comments" "comments-auth" "comments-reader" "comments-roots" "comments-edge" "thread"} component)
                                       (cond-> {"visual-comment" (cond-> comment-row
                                                                   (= "thread" component) (assoc :reply-count 2))}
                                         (= "comments-roots" component)
                                         (assoc "second-root" (assoc comment-row :id "second-root" :ts 1609459202000
                                                                                :title "Another root comment"
                                                                                :text "A separate root retains the original gap.")))
                                       {})
                            :loading? false
                            :more? false}))
              (r/reg-sub :comments/focus-ready? (fn [_ _] true))
              (r/reg-sub :comments/focus-state
                         (fn [_ _]
                           (if (= "standalone-comments" component)
                             {:ready? true
                              :comment-id "visual-comment"
                              :row comment-row
                              :root comment-row
                              :parent post-row}
                             {})))
              (when (#{"search-mixed" "search-extensions"} component)
                (let [response (if (= "search-extensions" component)
                                 extended-search-response
                                 search-response)]
                  (when (validation/explain search-model/result response)
                    (throw (js/Error. "Invalid native search fixture response")))
                  (r/reg-sub :search/open? (fn [_ _] true))
                  (r/reg-sub :search/query (fn [_ _] (:query response)))
                  (r/reg-sub :search/response (fn [_ _] response))
                  (r/reg-sub :search/pending? (fn [_ _] false))
                  (r/reg-sub :search/error (fn [_ _] nil))
                  (r/reg-sub :search/selected (fn [_ _] -1))
                  (r/reg-sub :search/get (fn [_ _] 0))))
              (when (= "github" component) (github-transport!))
              (when (= "strava" component) (strava-fixture/install-transport!))
              (when (= "post-editor" component) (post-editor-fixture/install! editor/profile))
              (when (#{"docs-index" "docs-topic" "instagram"} component)
                (public-read-transport!))
              (when (#{"docs-index" "docs-topic"} component)
                (r/dispatch-sync [:docs/set-page (if (= component "docs-index") "index" "docs")]))
              (restore/begin! (if (#{"docs-index" "docs-topic" "instagram" "shell-panels" "strava" "post-editor"} component) {} {:back? true}))
              (-> (loader/load! {:module :markdown})
                  (.then (fn [_]
                           (case component
                             "github" (loader/load! {:module :github})
                             ("docs-index" "docs-topic") (loader/acquire-code! :docs)
                             "instagram" (loader/acquire-code! :instagram)
                             "post-editor" (loader/load! {:module :blog-editor})
                             "strava" (-> (loader/load! {:module :maps})
                                          (.then (fn [_] (loader/load! {:module :carousel})))
                                          (.then (fn [_] (loader/load! {:module :strava}))))
                             nil)))
                  (.then (fn [_]
                           (r/render (r/create-root (.getElementById js/document "app"))
                             (case component
                               ("page-layout" "page-layout-vertical") [<layout-fixture> (= component "page-layout-vertical")]
                               ("search-mixed" "search-extensions") [<search-fixture>]
                               ("post" "comments" "comments-auth" "comments-reader" "comments-roots" "comments-edge" "thread")
                               [<post-fixture> component]
                               "standalone-comments" [<standalone-fixture>]
                               ("shell" "shell-scroll" "shell-panels" "header" "footer" "post-footer") [<shell-fixture> component]
                               "services" [<main-fixture> [home/<services> (editor/section :services false)]]
                               "cv" [<main-fixture> [cv/<cv>]]
                               "github" [<main-fixture> [github/<commits>]]
                               ("docs-index" "docs-topic") [<docs-fixture>]
                               "instagram" [<instagram-fixture>]
                               "strava" [strava-fixture/<fixture>]
                               "post-editor" [post-editor-fixture/<fixture>]
                               ("home-intro" "home-story" "home-image-media") [<home-fixture> component]
                               [<box-fixture> (= component "cv-container")])))))))))))
