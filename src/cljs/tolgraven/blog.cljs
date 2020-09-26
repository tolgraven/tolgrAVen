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

(defn preview-comment "Live md preview I guess. Prob best just ratom not db thing..."
  [model]
  (let [{:keys [user title md]} @model]
    [:<>
     [:h4 title]
     [:h6 (str "Posted by " user)] ; (format ts stuff)
     [:br]
     [ui/md->div md]]))

(declare add-comment)


(defn comment-post "A comment"
  [{:keys [id ts user title md]}]
  [:section.blog-comment
   [:h4 title]
   [:h6 (str "Posted by " user)] ; (format ts stuff)
   [:br]
   [ui/md->div md]])

(defn comments-section "Comments section!"
  [{:keys [id comments] :as blog-post}]
  [:section.blog-comments
   [:h6 (str (count comments) " comments")]
   (when comments
    (for [{:keys [id] :as post} comments] ^{:key (str "comment-post-" id)}
    [comment-post post]))
   [add-comment id]])

(defn add-comment "Post http or do a gql mutation, yada yada"
  [id]
  (let [model (r/atom {:user "" :title "" :md ""})
        box (fn [k & width]
              [:input {:type :textbox :style {:background-color "var(--bg-1)" :color "var(--fg-2)"}
                       :width (or width 60)
                       ; :display "inline-flex" :flex "1 1 auto" :width 180 :height 20
                       :on-change (fn [e]
                                    (let [new-val (-> e .-target .-value)]
                                      (swap! model assoc k new-val))) }])] ; tho stashing half-written in localstorage is p awesome when done. so db evt
    [:section.blog-add-comment
     [:h6 {:style {:text-align "right"}} "Add comment"]
     [preview-comment model]
     ; [comment-post @model]

     [box :user] [box :title]
     [box :md]   [:button {:on-click #(rf/dispatch [:blog-comment-new @model])}]
     ; (fn [e]
     ;   (when (= "Enter" (.-key e))
     ;     #(rf/dispatch [:blog-comment-new @model])))
     ; [preview-comment (rf/subscribe [:blog-comment-input])]])) ; well, a button and what not to open one, but yeah
     ])) ; well, a button and what not to open one, but yeah
; not here but whatever: thing from MYH site where heading slots into header

; blogs should be in a numbered map tho easiest
(defn blog-post "Towards a bloggy blag. Think float insets and stuff and, well md mostly heh"
  [{:keys [id ts user title md comments] :as blog-post}]
  [:section.blog-post ;.line-left
   [:h2 title]
   [:h6 (str "Posted by " (or user "tolgraven"))] ; (format ts stuff)
   [:br]
   [ui/md->div md]
   [:br] [:br]
   [comments-section blog-post]])

(defn blog "all the blogs"
  [blogs]
  [:section.blog
   [:h1 "MY BLOGS"]
   (for [post (reverse blogs)] ^{:key (str "blog-post-" (:id post))}
    [blog-post post])])

