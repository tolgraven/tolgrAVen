(ns website.visual-fixture
  "Fixed public content and native components for pixel/geometry comparisons."
  (:require [website.react :as r]
            [website.editor-visual :as editor]
            [website.component.restore :as restore]
            [website.modules.cv.views :as cv]
            [website.modules.blog.views :as blog]
            [website.modules.blog.comment-views :as comments]
            [website.modules.blog.events]
            [website.modules.blog.subs]
            [website.modules.blog-preview.views :as preview]
            [website.modules.search.views :as search]
            [website.components.shell :as shell]
            [website.modules.home.sections :as home]
            [website.loader :as loader]
            [website.navigation.routes :as routes]
            [reitit.frontend.easy :as rfe]
            [reitit.core :as reitit])
  (:require-macros [website.macros :as m]))

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

(m/defpage <fixture-page>
  "Use the native page-root container: direct grid children stretch to main's minimum height."
  [content]
  content)

(defn <main-fixture> [content]
  [:main#main.main-content.perspective-top
   [<fixture-page> content]])

(defn <search-fixture> []
  ;; The site's expandable Search panel is a main sibling, not a padded page
  ;; section. Its native input, suggestions, status and scroll clipping matter.
  [:<>
   [search/<ui>]
   [<main-fixture> [:section [:p "Fixed page content behind Search."]]]])

(defn <shell-fixture> [component]
  (let [content' #(editor/section % false)]
    [:<>
     (when (#{"header" "shell"} component)
       [shell/<header> (content' :header)])
     [<main-fixture> [:section [:h1 "Shared shell"] [:p "Fixed page content."]]]
     ;; Production footers are siblings of main. The full footer already owns
     ;; post-footer, including its font size and natural flex-row height.
     (when (#{"footer" "post-footer" "shell"} component)
       [shell/<footer-full> (content' :footer)])
     (when (= "shell" component)
       [shell/<footer> (content' :footer)])]))

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

(defn ^:export init! []
  (let [params (js/URLSearchParams. (.-search js/location))
        component (.get params "component")]
    (if-not (#{"cv-regular" "cv-container" "comments" "comments-auth" "comments-reader" "comments-roots" "comments-edge"
               "thread" "post" "standalone-comments" "search-mixed"
               "shell" "header" "footer" "post-footer" "services" "cv"} component)
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
              (when (#{"comments" "comments-auth" "comments-reader" "comments-roots" "comments-edge"
                       "thread" "post" "search-mixed"} component)
                (r/dispatch-sync [:set [:common/route]
                                  (assoc (reitit/match-by-path routes/router "/blog/post/42")
                                         :parameters {:path {:permalink "42"}, :query {}})]))
              (when (= "standalone-comments" component)
                (r/dispatch-sync [:set [:common/route]
                                  (assoc (reitit/match-by-path routes/router "/blog/comment/visual-comment")
                                         :parameters {:path {:comment-id "visual-comment"}, :query {}})]))
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
              (when (= "search-mixed" component)
                (r/reg-sub :search/open? (fn [_ _] true))
                (r/reg-sub :search/query (fn [_ _] (:query search-response)))
                (r/reg-sub :search/response (fn [_ _] search-response))
                (r/reg-sub :search/pending? (fn [_ _] false))
                (r/reg-sub :search/error (fn [_ _] nil))
                (r/reg-sub :search/selected (fn [_ _] -1))
                (r/reg-sub :search/get (fn [_ _] 0)))
              (restore/begin! {:back? true})
              (-> (loader/load! {:module :markdown})
                  (.then (fn [_]
                           (r/render (r/create-root (.getElementById js/document "app"))
                             (case component
                               "search-mixed" [<search-fixture>]
                               ("post" "comments" "comments-auth" "comments-reader" "comments-roots" "comments-edge" "thread")
                               [<post-fixture> component]
                               "standalone-comments" [<standalone-fixture>]
                               ("shell" "header" "footer" "post-footer") [<shell-fixture> component]
                               "services" [<main-fixture> [home/<services> (editor/section :services false)]]
                               "cv" [<main-fixture> [cv/<cv>]]
                               [<box-fixture> (= component "cv-container")])))))))))))
