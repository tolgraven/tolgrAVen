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

(defn <search-fixture> []
  [:main#main.main-content
   [:section.search-instant
    [:div.search-instant-results
     [preview/<post> hit]
     [preview/<comment> (-> hit
                           (assoc :preview :blog-preview/comment)
                           (assoc-in [:document :id] "comment-113")
                           (assoc-in [:document :kind] "blog-comment")
                           (assoc-in [:document :href] "/blog/post/42/comment/113"))]
     [preview/<post> (assoc-in hit [:document :id] "post-43")]]]])

(m/defpage <fixture-page>
  "Use the native page-root container: direct grid children stretch to main's minimum height."
  [content]
  content)

(defn <main-fixture> [content]
  [:main#main.main-content.perspective-top
   [<fixture-page> content]])

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
  [<main-fixture>
   [:section.cv.nopadding.noborder
    [:div.cv-boxes
     ;; Match the native timeline column in cv/<cv>. Absolutely positioned boxes
     ;; cannot establish their parent's width by themselves.
     [:div.cv-items.cv-work {:style {:width "200em", :height "9em"}}
      [cv/<box> (cond-> entry container?
                  (assoc :kind "container" :what "Festival day"
                         :children [(assoc entry :id "performance" :what "Main stage")]))
       "10%" "70%" 0 nil
       {:column 0, :category "work", :parents [], :parent-keys []}]]]]])

(defn <comment-fixture> [thread?]
  [:main#main.main-content
   [:div.blog.fullwide
    [:section.blog-comments
     [:div.blog-comments-inner
      [comments/<comment-post> {:path [42 "visual-comment"], :comment comment-row, :appear false}]
      (when thread?
        [comments/<comment-post>
         {:path [42 "second-comment"]
          :comment (assoc comment-row :id "second-comment" :title "A second comment"
                                 :text "Fixed prose next to the original corner actions.")
          :appear false}])]]]])

(defn <post-fixture> []
  [:main#main.main-content
   [:div.blog.fullwide
    [blog/<post-content>
     {:post {:id 42
             :user "visual-writer"
             :title "Native post header"
             :tags "clojure layout"
             :text "Fixed public post content with its actual author avatar."
             :ts 1609459200000}
      :features [[:appear false]]}]]])

(defn ^:export init! []
  (let [params (js/URLSearchParams. (.-search js/location))
        component (.get params "component")]
    (if-not (#{"cv-regular" "cv-container" "comments" "thread" "post" "search-mixed"
               "shell" "header" "footer" "post-footer" "services" "cv"} component)
      (editor/init! false)
      (-> (js/fetch "/fixtures/editor-seed.json")
          (.then #(.json %))
          (.then
            (fn [seed]
              (rfe/start! routes/router (fn [_ _]) {:use-fragment false})
              (r/dispatch-sync [:editor-visual/install (js->clj seed :keywordize-keys true) false])
              (when (#{"comments" "thread" "post" "search-mixed"} component)
                (r/dispatch-sync [:set [:common/route]
                                  (assoc (reitit/match-by-path routes/router "/blog/post/42")
                                         :parameters {:path {:permalink "42"}, :query {}})]))
              ;; Only provider boundaries are fixed. Native comment markup,
              ;; actions, clip measurement and expansion events are unchanged.
              (r/reg-sub :user/user
                         (fn [_ _]
                           (cond-> writer (= "post" component)
                             (assoc :avatar "/img/Leg-tasteful-small.jpg"))))
              (r/reg-sub :user/active-user (fn [_ _] nil))
              (r/reg-sub :comments/visible-thread (fn [_ _] []))
              (r/reg-sub :comments/root-page (fn [_ _] {:records {}, :loading? false, :more? false}))
              (r/reg-sub :comments/focus-state (fn [_ _] {}))
              (restore/begin! {:back? true})
              (-> (loader/load! {:module :markdown})
                  (.then (fn [_]
                           (r/render (r/create-root (.getElementById js/document "app"))
                             (case component
                               "search-mixed" [<search-fixture>]
                               "post" [<post-fixture>]
                               ("shell" "header" "footer" "post-footer") [<shell-fixture> component]
                               "services" [<main-fixture> [home/<services> (editor/section :services false)]]
                               "cv" [<main-fixture> [cv/<cv>]]
                               ("comments" "thread") [<comment-fixture> (= component "thread")]
                               [<box-fixture> (= component "cv-container")])))))))))))
