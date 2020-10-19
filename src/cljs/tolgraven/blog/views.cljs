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
     [:h4.blog-comment-title title]
     [:br]
     [ui/md->div text]]))

(declare add-comment)

(defn posted-by "Get details of blog/comment..."
  [id user ts score]
  (let [id (str "#" id) ; @(rf/subscribe [:uuid-to-presentation-id uuid])
        user [:em.blog-comment-user 
              (if (count user) user "anon")]
        by (str "posted by ")
        ts (util/timestamp ts)]
    [:span.blog-comment-info
     by user
     (str " - " ts
          " " (cond (pos? score) "+" (neg? score) "-") score)])) ;todo both score and upvote should fade in next to reply btn. but iffy now cause it's absolute etc


(defn comment-post "A comment, and any children."
  [path {:keys [id seq-id ts user title text score comments] :as post}]
  (let [vote-btn (fn [vote]
                   (let [voted @(rf/subscribe [:blog/state [:voted path]])]
                     [:button.blog-btn.blog-comment-vote-btn
                      {:class (if (= vote voted)
                                "noborder"
                                (case vote :up "topborder" :down "bottomborder"))
                       :on-click (fn [_] (rf/dispatch [:blog/comment-vote path vote]))}
                      (case vote :up "+" :down "-")]))]
    [:section.blog-comment
     [:h4.blog-comment-title title]
     [posted-by id user ts score]
     [:span.blog-comment-vote [vote-btn :up] [vote-btn :down]]
     [:div.blog-comment-text
      {:style (when (neg? score)
                {:filter (str "brightness(1 +"
                              (min 0.6 (* 0.1 score)) ")")})}
      [ui/md->div text]] [:br]
     (when comments ;replies
       [:<>
        (doall (for [post comments
                     :let [rk (reduce (fn [s i] (str s "-" i))
                                      "blog-comment"
                                      path)]]
                 ^{:key rk}
                 [comment-post (conj path id) post]))])
     [add-comment path :comment]])) ;reply button


(defn comments-section "Comments section!"
  [{:keys [id comments] :as blog-post}]
  (let [expanded? (rf/subscribe [:blog/state [:comments-expanded id]]) ;will have to be sub so add comment can auto expand
        amount-show-collapsed 3] ; then should dyn load more as scroll down hehu
    (fn [{:keys [id comments] :as blog-post}]
      (let [amount (count comments)
            amount-str (util/pluralize amount "comment")]
        [:section.blog-comments
         
         (if (<= amount amount-show-collapsed) ;only show a few comments unless expanded
           [:h6 amount-str]
           [:button.blog-btn.blog-btn-collapse.nomargin
            {:class (if @expanded? "noborder" "topborder")
             :on-click #(rf/dispatch [:blog/state [:comments-expanded id] (not @expanded?)])} ;rswap bc dont want to return false for react (not a problem here tho - why?) https://github.com/day8/re-frame/wiki/Beware-Returning-False
            (if-not @expanded? (str "Show all " amount-str) "Collapse")])
         
         (when comments
           [:div.blog-comments-inner
            {:class (when-not @expanded? "collapsed")}
            (doall (for [comment (if @expanded?
                                   comments
                                   (take amount-show-collapsed comments))]
                     ^{:key (str "blog-post-" (:id blog-post) "-comment-" (:id comment))}
                     [comment-post [(:id blog-post) (:id comment)] comment]))])
         
         [add-comment [id] :blog]])))) ;new comment button


(defn add-comment "Post http or do a gql mutation, yada yada"
  [parent-path parent-type] ; TODO id as id-path (or, unique for all but presented by index...)
  ; id could also be a map... {:uuid adfskl :id 1 :kind :comment :path [:1 :3 :7]} ;where 1 blog, 3 and 7 comments
  (let [adding-comment? @(rf/subscribe [:blog/state [:adding-comment parent-path]]) ;test... would need id of post
        model (r/atom {:user "anon" :title "" :text ""})
        input-valid? (fn [input]
                       (pos? (count (:text input))))
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
                                       [:blog/state [:adding-comment parent-path] (not adding-comment?)])}]
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
                       :disabled (when-not (input-valid? @model) true)
                       :on-click (fn [_]
                                   (if (and logged-in?
                                            (input-valid? @model))
                                     (do (rf/dispatch [:blog/state [:adding-comment parent-path] false])
                                         (rf/dispatch [:blog/comment-new parent-path @model]))
                                     (rf/dispatch [:state [:login-view-open] true])))}
                      "Submit"])
        valid-bg {:background-color "#182018"}] ; tho stashing half-written in localstorage is p awesome when done. so db evt}]] ; tho stashing half-written in localstorage is p awesome when done. so db evt
     (if-not adding-comment?
       [toggle-ui-btn parent-type]
       [:section.blog-adding-comment
        [box :title :input :style valid-bg :ui-name "Title (optional)"]
        [box :text :textarea :ui-name "comment"] ;[:br]
        [submit-btn] [toggle-ui-btn :cancel]
        [preview-comment model]])))

     ; :on-key-up (fn [e] (when (= "Alt-Enter-however-written" (.-key e)) (submit)))
; not here but whatever: thing from MYH site where heading slots into header

(defn preview-blog "Render new post preview"
  [model]
  (let [{:keys [title text]} @model]
    [:div.blog-post-preview
     [:h4.blog-post-title title]
     [:br]
     [ui/md->div text]]))

(defn blog-new-post-ui "Render post-making ui" []
  (let [model (r/atom :title "" :text "")
        on-change (fn [k]
                    (fn [e]
                      (let [new-val (-> e .-target .-value)]
                        (swap! model assoc k new-val))))]
    [:section.blog-new-post
     [:input.blog-new-post-title
      {:type :textbox
       :name "Title"
       :placeholder "Title"
       :on-change (on-change :title)}]
     [:textarea.blog-new-post-text
      {:name "Text"
       :placeholder "Text (markdown)"
       :on-change (on-change :text)}]
     
     [preview-blog model]]))

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

(defn blog-nav "Blog navigation buttons"
  [total-posts current-idx posts-per-page]
  (let [nav-btn (fn [nav label & [attrs]]
                  [:button.blog-btn.blog-nav-btn.topborder
                   (merge {:on-click (fn [_] (rf/dispatch [:blog/nav-page nav]))}
                          attrs)
                   label])
        nav-idxs (doall (for [i (range (/ total-posts posts-per-page))] ^{:key (str "blog-nav-btn-" i)}
                          [nav-btn i (inc i) (when (= i current-idx)
                                               {:class "current"})]))
        back-btn (when-not (= 0 current-idx)
                   [nav-btn :prev "<<"])
        fwd-btn (when-not (<= total-posts (* posts-per-page (inc current-idx)))
                  [nav-btn :next ">>"])]
    [:div.blog-nav.center-content
      back-btn nav-idxs fwd-btn]))

(defn blog "all the blogs"
  []
  (let [per-page 2
        total @(rf/subscribe [:blog/count])
        idx @(rf/subscribe [:blog/state [:page]])
        posts @(rf/subscribe [:blog/posts-for-page idx per-page]) ]
    [:section.blog.fullwide.noborder ;then chuck flip-move on eeet. or just same slide nav thing
     (doall (for [post posts] ^{:key (str "blog-post-" (:id post))}
              [blog-post post]))
     [blog-nav total idx per-page]
     [:br] ]))

