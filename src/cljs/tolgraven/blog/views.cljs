(ns tolgraven.blog.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   ; [cljsjs.codemirror :as codemirror]
   ; [cljsjs.codemirror.keymap.vim]
   ; [cljsjs.codemirror.mode.markdown]
   [markdown.core :refer [md->html]]
   [clojure.string :as string]
   [tolgraven.util :as util :refer [at]]
   [tolgraven.views-common :as common]
   [tolgraven.ui :as ui]))
;; should be a rather large overlapping functionality post blog/post comment
;; will need logged in user and all dis jazz. But still important differences so urgh

(defn preview-comment "Live md preview I guess. Prob best just ratom not db thing..."
  [model]
  (let [{:keys [user title text]} @model]
    [:div.blog-comment-preview
     ; {:min-height "needs to match up with (auto expanding) comment box somehow..."}
     {:style {:min-height "8rem"}}
     (when title
       [:h4.blog-comment-title title])
     [:br]
     [ui/md->div text]]))

(declare add-comment)

(defn posted-by "Get details of blog/comment..."
  [id user ts score]
  (let [user [:em.blog-user 
              (if (count user) user "anon")]
        by (str "posted by ")
        ts (util/timestamp ts)]
    [:span.blog-info
     ; (str "#" id " ") by
     user
     [:span ts]
     [:span (cond (pos? score) "+" (neg? score) "-")
      (when-not (= 0 score) score)]])) ;todo both score and upvote should fade in next to reply btn. but iffy now cause it's absolute etc

(defn add-comment-btn "Seemed like a good idea to swap button for input field when pressed but yeah, no..."
  [parent-path kind]
  (let [adding-comment? @(rf/subscribe [:blog/state [:adding-comment parent-path]])
        attrs {:on-click
               #(rf/dispatch
                 [:blog/state [:adding-comment parent-path] (not adding-comment?)])}]
    (case kind
      :comment (when-not adding-comment?
                 [:button.blog-btn.topborder
                  attrs "Add comment"])
      :reply   (when-not adding-comment?
                 [:button.blog-btn.blog-comment-reply-btn.noborder
                  attrs [:i.fa.fa-reply]])
      :cancel  (when adding-comment?
                 [:button.blog-btn.bottomborder
                  attrs "Cancel"]))))

(defn edit-comment
  [path]
  [:button.blog-btn.blog-comment-edit-btn.noborder
   [:i.fa.fa-edit]]) ;put by reply yo

(defn delete-comment ;well that's when seq-id breaks down anyways lol
  [path]
  [:div "whua"])

(defn- get-id-str [path]
  (reduce (fn [s i]
            (str s "-" i))
          (str "blog-post-" (first path) "-comment")
          (rest path)))


(defn comment-post "A comment, and any children."
  [path {:keys [id seq-id ts user title text score comments] :as post}]
  (let [active-user @(rf/subscribe [:user/active-user])
        user @(rf/subscribe [:user/user user])
        vote-btn (fn [vote]
                   (when active-user
                     (let [voted @(rf/subscribe [:blog/state [:voted path]])]
                       [:button.blog-btn.blog-comment-vote-btn
                        {:class (if (= vote voted)
                                  "noborder"
                                  (case vote :up "topborder" :down "bottomborder"))
                         :on-click (fn [_] (rf/dispatch [:blog/comment-vote path vote]))}
                        (case vote :up "+" :down "-")])))]
    [:<>
     [:section.blog-comment
      [:div.flex
       [:img.user-avatar
        {:src (or (:avatar user) ; NOTE (get ... default) apparently not safe?? or works for same op.
                  @(rf/subscribe [:user/default-avatar]))}]
       
       [:div.blog-comment-main
        [:h4.blog-comment-title title]
        [posted-by id (:name user) ts score]
        (when (not= active-user user)
          [:span.blog-comment-vote [vote-btn :up] [vote-btn :down]])
        [:div.blog-comment-text
         {:style {:filter (when (neg? score)
                            (str "brightness(calc(1 + "
                                 (max -0.6 (* 0.1 score)) "))"))}}
         [ui/md->div text]]]
       
       [:div.blog-comment-actions
        (when (= active-user user)
          [edit-comment (conj path id)])
        (when active-user
          [add-comment-btn path :reply])]]] 
     [add-comment path]
     (when comments ;replies
       [:div.blog-comment-reply
        (doall (for [[k post] (into (sorted-map) comments)]
                 ^{:key (get-id-str (conj path (:id post)))}
                 [comment-post (conj path (:id post)) post]))])]))


(defn comments-section "Comments section!"
  [{:keys [id comments] :as blog-post}] ;actually apart from id -> path could prob call this again for replies. w right css
  (let []
    (let [expanded? (rf/subscribe [:blog/state [:comments-expanded id]]) ;will have to be sub so add comment can auto expand
          amount-show-collapsed 3
          amount-str (util/pluralize (count comments) "comment")] ; then should dyn load more as scroll down hehu
      [:section.blog-comments
       (if (<= (count comments) amount-show-collapsed) ;only show a few comments unless expanded
         [:h6.bottomborder amount-str]
         [:button.blog-btn.blog-collapse-btn.nomargin.bottomborder
          {;:class (if @expanded? "bottomborder" "topborder")
           :on-click #(rf/dispatch [:blog/state [:comments-expanded id] (not @expanded?)])} ;rswap bc dont want to return false for react (not a problem here tho - why?) https://github.com/day8/re-frame/wiki/Beware-Returning-False
          (if-not @expanded? (str "Show all " amount-str) "Collapse")])

       (when (seq comments)
         (let [comments' (vals (into (sorted-map) comments))]
           [:div.blog-comments-inner
            {:class (when-not @expanded? "collapsed")}
            (doall (for [comment (if @expanded?
                                   comments'
                                   (take amount-show-collapsed comments'))
                         :let [path [(:id blog-post) (:id comment)]]]
                     ^{:key (get-id-str path)}
                     [comment-post path comment]))]))

       (when @(rf/subscribe [:user/active-user])
         [add-comment-btn [id] :comment]) ;new comment button
       [add-comment [id]]]))) ;usually nil



(defn add-comment "Post http or do a gql mutation, yada yada"
  [parent-path]
  (let [adding-comment? (rf/subscribe [:blog/state [:adding-comment parent-path]])
        model (r/atom {:title "" :text ""}) ;should use db tho, no good accidental nav and lose shit
        input-valid? (fn [input]
                       (pos? (count (:text input))))
        preview? (r/atom false)
        box (fn [k kind & {:keys [style ui-name]}]
              [kind ; diverging too much, sep...
               {:class (str "blog-adding-comment-textbox")
                :type :textbox
                :value (get @model k)
                :name (or ui-name (name k))
                :placeholder (string/capitalize (or ui-name (name k)))
                :style (merge (when (= kind :textarea)
                                {:min-height (-> @model :text ;sadly linebreak doesnt count so only expands once text on newline.
                                                 string/split-lines
                                                 count (+ 2)
                                                 util/rem-to-px)})
                              style)
                :on-change (fn [e]
                             (let [new-val (-> e .-target .-value)]
                               (swap! model assoc k new-val)))}]) ; tho stashing half-written in localstorage is p awesome when done. so db evt
        submit-btn (fn []
                     [:button.blog-btn.noborder
                      {:class    (when (input-valid? @model) "topborder")
                       :disabled (when-not (input-valid? @model) true)
                       :on-click (fn [_]
                                   (when (input-valid? @model)
                                     (rf/dispatch [:blog/state [:adding-comment parent-path] false])
                                     (rf/dispatch [:blog/comment-new parent-path @model])
                                     (reset! model nil)))}
                      "Submit"])
        valid-bg {:background-color "#182018"}] ; tho stashing half-written in localstorage is p awesome when done. so db evt}]] ; tho stashing half-written in localstorage is p awesome when done. so db evt
     (fn [parent-path] ; needed or recreates to empty when swapped out
       (when @adding-comment?
         [:div.blog-adding-comment
          [:button.blog-btn
           {:on-mouse-over #(reset! preview? true)
            :on-mouse-leave #(reset! preview? false)}
           "Preview"] [:br]
          (if @preview?
            [preview-comment model]
            [:<>
             [box :title :input :style valid-bg :ui-name "Title (optional)"]
             [box :text :textarea :ui-name "Comment"]]) ;[:br]
          [submit-btn] [add-comment-btn parent-path :cancel]
          
          ]))))

     ; :on-key-up (fn [e] (when (= "Alt-Enter-however-written" (.-key e)) (submit)))
; not here but whatever: thing from MYH site where heading slots into header

(defn preview-blog "Render new post preview"
  [{:keys [title text]}]
  [:div
   ; {:ref #(rf/dispatch [:run-highlighter!])} ;screws up live preview :( jumpy
    [:h2 title]
    [:br]
    [ui/md->div text]])

(defn post-blog "Render post-making ui" [] ; XXX move this and similar to own file...
  (let [input @(rf/subscribe [:form-field [:post-blog]])
        user @(rf/subscribe [:user/active-user])]
    [:section.blog.blog-new-post
     [:h2 "Write new blog post"]
     [:br]

     [ui/input-text
      :placeholder "Title"
      :path [:form-field [:post-blog :title]]
      :on-change #(rf/dispatch [:form-field [:post-blog :title] %])]
     
     [ui/input-text :input-type :textarea
      :placeholder "Text (markdown)"
      :height "40vh"
                :min-rows 6
      :width "100%"
      :path [:form-field [:post-blog :text]]
      :on-change #(rf/dispatch [:form-field [:post-blog :text] %])]
     
     [:br]
     [ui/button "Save draft" :save-blog-draft] ;should save to firebase etc.
     [ui/button "Highlight code" :highlight-blog-code
      :action #(rf/dispatch [:run-highlighter!])]
     [:br]
     [:section.blog-post-preview
      [preview-blog input]]
     
     [:section
      [ui/button "Post" :post-new-blog
       :action #(do (rf/dispatch [:blog/submit-new (merge {:user user} input)])
                    (rf/dispatch [:common/navigate! :blog]))]]]))


(defn blog-post "Towards a bloggy blag. Think float insets and stuff and, well md mostly heh"
  [{:keys [id ts user title text comments] :as blog-post}]
  (let [user @(rf/subscribe [:user/user user])]
    [:section.blog-post
     {:ref #(rf/dispatch [:run-highlighter! %])}
     [:div.flex.blog-post-header
      [:img.user-avatar.blog-user-avatar
       {:src (get user :avatar @(rf/subscribe [:user/default-avatar]))}]
      [:div.blog-post-header-main
       [:h1 title]
       [posted-by id (:name user) ts]
       [:div.blog-post-tags
        (doall (for [tag (or (:tags blog-post) ["some-category" "other" "random thoughts"])]
                 ^{:key (str "blog-post-" id "-category-" tag)}
                 [:span tag]))]]]
     [:br]
     ; [a custom sticky mini "how far youve scrolled bar" on right?]
     [:div.blog-post-text [ui/md->div text]]
     [:br] [:br]
     [comments-section blog-post]]))

(defn blog-archive-view "List of all posts with headlines etc. Maybe for a sidebar."
  ; will need:
  ; dispatch fetch headlines? - or like who am i kidding dont need to be super scaly best practices right away...
  ; prob enough not attempt render -> not loading images?
  []
  (let [posts @(rf/subscribe [:blog/posts])]
    [:section.blog-archive
     (for [{:keys [id ts user title text comments] :as post} posts] ^{:key (str "blog-archive-" (:id post))}
       [:div
        [:h3 title]
        [:p (->> text string/split-lines (take 3))]])]))

(defn blog-tags-cloud "Tin"
  [])

(defn blog-intros-view "Headline and a paragraph, many on each page."
  [])

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
  (let [total @(rf/subscribe [:blog/count])
        per-page (min total @(rf/subscribe [:blog/posts-per-page]))
        idx @(rf/subscribe [:blog/state [:page]])
        posts @(rf/subscribe [:blog/posts-for-page idx per-page]) ]
    [:section.blog.fullwide.noborder ;then chuck flip-move on eeet. or just same slide nav thing
     (if (pos? total)
       [:<>
        (doall (for [post posts] ^{:key (str "blog-post-" (:id post))}
                    [blog-post post]))
        [blog-nav total idx per-page]]
       ; [:h1.center-content "No posts yet."])
       [:h1.center-content [common/loading-spinner true]])
     [:br] ]))

