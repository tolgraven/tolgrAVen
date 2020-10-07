(ns tolgraven.blog.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [re-graph.core :as rg]
   [markdown.core :refer [md->html]]
   [clojure.string :as string]
   [tolgraven.util :as util :refer [at ors]]
   [tolgraven.ui :as ui]))
;; should be a rather large overlapping functionality post blog/post comment
;; will need logged in user and all dis jazz. But still important differences so urgh

(defn preview-comment "Live md preview I guess. Prob best just ratom not db thing..."
  [model]
  (let [{:keys [user title text]} @model]
    [:div.blog-comment-preview
     [:h4 title]
     [ui/md->div text]]))

(declare add-comment)

(defn posted-by "Get details of blog/comment..."
  [id user ts]
  (let [id (str "#" id) ; @(rf/subscribe [:uuid-to-presentation-id uuid])
        user [:em {:style {:font-weight "bold"}}
              (if (count user) user "anon")]
        by (str "posted by ")
        ts (util/timestamp ts)]
    [:h6 (str id " - " by) user (str " - " ts)]))

(defn comment-post "A comment"
  [parent-path {:keys [id ts user title text comments] :as post}]
  [:section.blog-comment
   [:h4 title]
   [posted-by id user ts] [:br]
   [ui/md->div text] [:br]
   (when comments ;replies
       [:<> ;best if could recurse back to comments-section...
        (doall (for [post comments
                     :let [rk (reduce (fn [s i] (str s "-" i))
                                    "blog-comment"
                                    parent-path)]]
               ; ^{:key (str "blog-post-" (first path) "-comment-" parent-id "-reply-" (:id post))}
               ^{:key rk}
                 [comment-post (conj parent-path (:id post)) post]))])
   [add-comment parent-path :comment]])

(defn comments-section "Comments section!"
  [{:keys [id comments] :as blog-post}]
  (let [expanded? (r/atom false)
        amount (count comments)]
    [:section.blog-comments
     [:h6 (util/pluralize amount "comment")]
     (when comments
       (doall (for [comment comments] ^{:key (str "blog-post-" (:id blog-post) "-comment-" (:id comment))}
                [comment-post [(:id blog-post) (:id comment)] comment])))
     [add-comment [id] :blog]]))

(defn add-comment "Post http or do a gql mutation, yada yada"
  [parent-path parent-type] ; TODO id as id-path (or, unique for all but presented by index...)
  ; id could also be a map... {:uuid adfskl :id 1 :kind :comment :path [:1 :3 :7]} ;where 1 blog, 3 and 7 comments
  (let [adding-comment? @(rf/subscribe [:state [:blog :adding-comment parent-path]]) ;test... would need id of post
        model (r/atom {:user "anon" :title "" :text ""})
        submit #(rf/dispatch [:blog/comment-new parent-path @model])
        input-valid? (fn [input]
                       (pos? (count (:text input))))
                       ; (every? (fn [[_ v]] (pos? (count v)))
                       ;         (-> input dissoc :title)))
        logged-in? (or @(rf/subscribe [:state [:login :session :status]])
                       true) ;temp
        box (fn [k kind & {:keys [style ui-name]}]
              [kind
               {:class (str "blog-adding-comment-textbox")
                :type :textbox
                :name (or ui-name (name k))
                :placeholder (string/capitalize (or ui-name (name k)))
                :style style
                :on-change (fn [e]
                             (let [new-val (-> e .-target .-value)]
                               (swap! model assoc k new-val)))}]) ; tho stashing half-written in localstorage is p awesome when done. so db evt
        toggle-ui-btn (fn [kind]
                        (let [attrs {:on-click
                                     #(rf/dispatch
                                       [:toggle [:state :blog :adding-comment parent-path]])}]
                          (case kind
                            :blog [:button.blog-btn.topborder
                                   attrs "Add comment"]
                            :comment [:button.blog-btn.blog-reply-comment-btn.topborder
                                      attrs "Reply"]
                            :cancel [:button.blog-btn.bottomborder
                                     attrs "Cancel"])))
        submit-btn (fn []
                     [:button.blog-btn.noborder
                      {:class (when (input-valid? @model) "topborder")
                       :disabled (when-not (input-valid? @model) :true)
                       :on-click (fn [_]
                                   (if (and logged-in?
                                            (input-valid? @model))
                                     ; (do (rf/dispatch [:state [:blog :adding-comment parent-path] false])
                                     (do (rf/dispatch [:unset [:state :blog :adding-comment parent-path]])
                                         (submit))
                                     (rf/dispatch [:state [:login-view-open] true])))}
                      "Submit"])
        valid-bg {:background-color "#182018"}] ; tho stashing half-written in localstorage is p awesome when done. so db evt}]] ; tho stashing half-written in localstorage is p awesome when done. so db evt
     (if-not adding-comment?
       [toggle-ui-btn parent-type]
       [:section.blog-adding-comment.paad ;{:style {:padding "1em"}}
        [box :title :input :style valid-bg :ui-name "Title (optional)"]
        [box :text :textarea :ui-name "comment"] ;[:br]
        [submit-btn] [toggle-ui-btn :cancel]
        [preview-comment model]])))

     ; :on-key-up (fn [e] (when (= "Alt-Enter-however-written" (.-key e)) (submit)))
; not here but whatever: thing from MYH site where heading slots into header

(defn blog-new-post-ui "Render post-making ui" []
  )

; blogs should be in a numbered map tho easiest
(defn blog-post "Towards a bloggy blag. Think float insets and stuff and, well md mostly heh"
  [{:keys [id ts user title text comments] :as blog-post}]
  [:section.blog-post
   [:h2 title]
   [posted-by id (or user "tolgraven") ts]
   [:br]
   [ui/md->div text]
   [:br] [:br]
   [comments-section blog-post]])

(defn blog "all the blogs"
  []
  (let [per-page 2
        total @(rf/subscribe [:blog/count])
        idx @(rf/subscribe [:state [:blog :page]])
        posts @(rf/subscribe [:blog/posts-for-page idx per-page])
        nav-btn (fn [nav label & [attrs]]
                  [:button.blog-btn.blog-nav-btn.topborder
                   (merge {:on-click (fn [_] (rf/dispatch [:blog/nav-page nav]))}
                          attrs)
                   label])]
    [:section.blog.fullwide.noborder ;then chuck flip-move on eeet. or just same slide nav thing
     (doall (for [post posts] ^{:key (str "blog-post-" (:id post))}
                 [blog-post post]))
     [:div.center-content
      (when-not (= 0 idx)
        [nav-btn :prev "<<"])
      (doall (for [i (range (/ total per-page))]
               [nav-btn i (inc i) (when (= i idx)
                                    {:style {:font-weight :bolder
                                             :border 0
                                             :border-bottom "var(--line-width-sm) solid"}})]))
      (when-not (<= total (* per-page (inc idx)))
        [nav-btn :next ">>"])]
     [:br]
     [:div idx]]))

