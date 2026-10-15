(ns website.visual-fixture
  "Fixed public content and native components for pixel/geometry comparisons."
  (:require [website.react :as r]
            [website.editor-visual :as editor]
            [website.component.restore :as restore]
            [website.modules.cv.views :as cv]
            [website.modules.home.views :as home]
            [website.page-layout :as page-layout]
            [website.modules.blog.comment-views :as comments]
            [website.modules.blog.events]
            [website.modules.blog.subs]
            [website.modules.blog-preview.views :as preview]
            [website.components.shell :as shell]
            [website.loader :as loader]
            [website.navigation.routes :as routes]
            [reitit.frontend.easy :as rfe]
            [reitit.core :as reitit]))

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
              :tags "clojure,layout"}
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

(defn <shell-fixture> []
  (let [content' #(editor/section % false)]
    [:<>
     [shell/<header> (content' :header)]
     [:main#main.main-content [:section [:h1 "Shared shell"] [:p "Fixed page content."]]]
     [shell/<footer-full> (content' :footer)]
     [shell/<post-footer> (content' :post-footer)]]))

(defn <box-fixture> [container?]
  [:main#main.main-content
   [:section.cv
    [:div.cv-boxes {:style {:height "28rem"}}
     [:div.cv-items
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

(defn <layout-fixture> [vertical?]
  [:main#main.main-content
   [home/<layout>
    (let [page (page-layout/find-page page-layout/defaults "experiments")
          page' (update page :sections (fn [sections] (filterv #(#{"story" "page-index"} (:component %)) sections)))
          [story index] (:sections page')]
      (if vertical?
        (-> page'
            (dissoc :columns)
            (assoc :sections [index
                              (update-in story [:content :text] #(str % "\n\n" % "\n\n" %))
                              (assoc story :identity "second-story", :label "Second article")]))
        page'))]])


(defn ^:export init! []
  (let [params (js/URLSearchParams. (.-search js/location))
        component (.get params "component")]
    (if-not (#{"page-layout" "page-layout-vertical" "cv-regular" "cv-container" "comments" "thread" "search-mixed" "shell"} component)
      (editor/init! false)
      (-> (js/fetch "/fixtures/editor-seed.json")
          (.then #(.json %))
          (.then
            (fn [seed]
              (rfe/start! routes/router (fn [_ _]) {:use-fragment false})
              (r/dispatch-sync [:editor-visual/install (js->clj seed :keywordize-keys true) false])
              (r/dispatch-sync [:set [:common/route]
                                (cond-> (assoc (reitit/match-by-path routes/router "/blog/post/42")
                                       :parameters {:path {:permalink "42"}, :query {}})
                                  (#{"page-layout" "page-layout-vertical"} component)
                                  (assoc :path (.-pathname js/location), :query-params {:component component}))])
              ;; Only provider boundaries are fixed. Native comment markup,
              ;; actions, clip measurement and expansion events are unchanged.
              (r/reg-sub :user/user (fn [_ _] writer))
              (r/reg-sub :user/active-user (fn [_ _] nil))
              (r/reg-sub :comments/visible-thread (fn [_ _] []))
              (restore/begin! {:back? true})
              (-> (loader/load! {:module :markdown})
                  (.then (fn [_]
                           (r/render (r/create-root (.getElementById js/document "app"))
                             (case component
                               ("page-layout" "page-layout-vertical") [<layout-fixture> (= component "page-layout-vertical")]
                               "search-mixed" [<search-fixture>]
                               "shell" [<shell-fixture>]
                               ("comments" "thread") [<comment-fixture> (= component "thread")]
                               [<box-fixture> (= component "cv-container")])))))))))))
