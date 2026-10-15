(ns website.modules.blog-preview.views
  "Search presentation shares native blog metadata without acquiring full posts or threads."
  (:require [website.macros :as m :refer-macros [defc]]
            [website.react :as r]
            [website.modules.blog.metadata :as metadata]
            [website.modules.search.model :as model]
            [website.modules.search.presentation :as presentation])
  (:require-macros [website.build.site :refer [when-enabled]]))

(defc <post> [{:keys [document title excerpt]} :- model/hit]
  [:div.blog
   [:article.search-instant-result.blog-post
    [:div.blog-post-header-main
     [:a {:href (:href document)} [:h2.blog-post-title [presentation/<fragments> title]]]
     [metadata/<posted-by> document]
     [metadata/<tags-list> {:post document}]]
    [:p [presentation/<fragments> excerpt]]]])

(when-enabled :blog :comments
 (defc <comment> [{:keys [document title excerpt]} :- model/hit]
  (let [user @(r/subscribe [:user/user (:user document)])]
    [:div.blog-comments
     [:div.blog-comments-inner
      [:div.search-instant-result.blog-comment-around.flex
       [:div.blog-comment-border]
       [:section.blog-comment
        [:div
         (when-enabled :user nil (m/<> :user/avatar user))
         [:div.blog-comment-main
          [:a {:href (:href document)} [:h4.blog-comment-title [presentation/<fragments> title]]]
          [metadata/<posted-by> document]
          [:p.blog-comment-text [presentation/<fragments> excerpt]]]]]]]])))
