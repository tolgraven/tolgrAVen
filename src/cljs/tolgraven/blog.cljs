(ns tolgraven.blog
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [re-graph.core :as rg]
   [markdown.core :refer [md->html]]
   [clojure.string :as string]
   [tolgraven.util :as util :refer [at]]
   [tolgraven.ui :as ui]))
;; should be a rather large overlapping functionality post blog/post comment
;; will need logged in user and all dis jazz. But still important differences so urgh

(defn show-md [md]
  [:div {:dangerouslySetInnerHTML {:__html (md->html md)}}])

(defn preview-comment "Live md preview I guess. Prob best just ratom not db thing..."
  [model]
  ; [show-md (at model "")])
  [show-md "what"])

(defn add-comment "Post http or do a gql mutation, yada yada"
  [id]
  (let [model (r/atom "")]
    [:section.blog-add-comment
     #_(ui/input-text {;:value model
                     :path [:blog-comment-input]
                     :on-enter #(rf/dispatch [:blog-comment-new id %])})
     #_[preview-comment (rf/subscribe [:blog-comment-input])]])) ; well, a button and what not to open one, but yeah

(defn comment-post "A comment"
  [{:keys [id ts user title md]}]
  [:section.blog-comment
   [show-md md]])

(defn comments-section "Comments section!"
  [{:keys [id comments] :as blog-post}]
  [:section.blog-comments
   [:h6 (str (count comments) " comments")]
   (when comments
    (for [post comments]
    [comment-post post]))
   [add-comment id]])



(defn blog-post "Towards a bloggy blag. Think float insets and stuff and, well md mostly heh"
  [{:keys [id ts user title md comments] :as blog-post}]
  [:section.blog-post
   [:h3 title]
   [:h6 (str "Posted by " (or user "tolgraven"))] ; (format ts stuff)
   [:br]
   [show-md md]
   [:br] [:br]
   [comments-section blog-post]])

(defn blog "all the blogs"
  [blogs]
  [:section.blog
   [:h1 "MY BLOGS"]
   (for [post (reverse blogs)] ^{:key (str "blog-post-" (:id post))}
    [blog-post post])])

