(ns tolgraven.blog.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [reitit.frontend.easy :as rfe]
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
(declare blog-container)

(defn posted-by "Get details of blog/comment..."
  [id user ts score]
  (let [user [:em.blog-user 
              (if (count (:name user)) (:name user) "anon")]
        by (str "posted by ")
        ts (util/timestamp ts)]
    [:span.blog-info
     user
    (when (some #{(:id user)} (:admins @(rf/subscribe [:<-store :auth :roles])))
      [:span "(admin)"])
     [:span ts]
     (when-not (= 0 score)
       [:span (cond (pos? score) "+"
                    (neg? score) "-")
        score])])) ;todo both score and upvote should fade in next to reply btn. but iffy now cause it's absolute etc

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

(defn collapsed-reply-view
  [path parent-id comments]
  (let [inited? (r/atom false)]
    (fn [path parent-id comments]
      [:div.blog-comment-reply.flex
       {:style {:cursor "zoom-in"
                :transition "max-height 1s ease"
                :max-height (if @inited? "3rem" 0)}
        :ref #(when % (reset! inited? true))
        :on-click (fn [e]
                    (rf/dispatch [:blog/state [:comment-thread-uncollapsed path] true])
                    (doseq [[k post] comments]
                      (rf/dispatch [:blog/state [:comment-thread-uncollapsed
                                                 (conj path (:id post))]
                                    true])))}
       [:div.blog-comment-border]
       [:section.blog-comment.blog-comment-collapsed-placeholder
        (util/pluralize (count comments) " hidden reply")]])))

(defn comment-post "A comment, and any children."
  [path {:keys [id seq-id ts user title text score comments] :as post}]
  (let [active-user @(rf/subscribe [:user/active-user])
        user @(rf/subscribe [:user/user user])
        uncollapsed? (rf/subscribe [:comments/thread-uncollapsed? path])
        vote-btn (fn [vote]
                   (when active-user
                     (let [voted @(rf/subscribe [:blog/state [:voted path]])]
                       [:button.blog-btn.blog-comment-vote-btn
                        {:class (if (= vote voted)
                                  "noborder"
                                  (case vote :up "topborder" :down "bottomborder"))
                         :disabled (when (= vote voted) true)
                         :on-click #(rf/dispatch [:blog/comment-vote 
                                                  user active-user path vote])}
                        (case vote :up "+" :down "-")])))]
    [:<>
      [:div.flex.blog-comment-around
       [:div.blog-comment-border
        {:style {:cursor (if @uncollapsed? "zoom-out" "zoom-in")
                 :background-color (:bg-color user)} ; somehow doesnt fly, why?
         :on-click #(rf/dispatch [:blog/state [:comment-thread-uncollapsed path]
                                  (not @uncollapsed?)])}]
     [:section.blog-comment
      [:div
       [ui/user-avatar user]
       
       [:div.blog-comment-main
        [:h4.blog-comment-title title]
        [posted-by id user ts score]
        (when (not= active-user user)
          [:span.blog-comment-vote [vote-btn :up] [vote-btn :down]])
        [:div.blog-comment-text
         {:style {:filter (when (neg? score)
                            (str "brightness(calc(1 + "
                                 (max -0.7 (* 0.1 score)) "))"))}}
         [ui/md->div text]]]
       
       [:div.blog-comment-actions
        (when (= active-user user)
          [edit-comment (conj path id)])
        (when active-user
          [add-comment-btn path :reply])]]]] 
     [add-comment path]
     (if comments ;replies
       [:div.blog-comment-reply-outer
        [:div.blog-comment-reply
         {:class (when-not @uncollapsed?
                   "collapsed")}
         (doall (for [[k post] (into (sorted-map) comments)]
                  ^{:key (get-id-str (conj path (:id post)))}
                  [ui/appear-anon "slide-behind"
                   [comment-post (conj path (:id post)) post]]))]
        (when-not @uncollapsed?
          [collapsed-reply-view path id comments])])]))


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
        editing (rf/subscribe [:blog/state [:editing-comment parent-path]]) ;XXX would break when multiple replies to same parent
        model (rf/subscribe [:form-field [:write-comment parent-path]]) ;should use db tho, no good accidental nav and lose shit
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
                               (rf/dispatch-sync [:form-field [:write-comment parent-path k] new-val])))}]) ; tho stashing half-written in localstorage is p awesome when done. so db evt
        submit-btn (fn []
                     [:button.blog-btn.noborder
                      {:class    (when (input-valid? @model) "topborder")
                       :disabled (when-not (input-valid? @model) true)
                       :on-click (fn [_]
                                   (when (input-valid? @model)
                                     (rf/dispatch [:blog/state [:adding-comment parent-path] false])
                                     (rf/dispatch [:blog/comment-submit parent-path @model @editing])
                                     (rf/dispatch [:form-field [:write-comment parent-path] nil])))}
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
    [:h2.blog-post-title title]
    [:br]
    [ui/md->div text]])

(defn post-blog "Render post-making ui" [] ; XXX move this and similar to own file...
  (let [input @(rf/subscribe [:form-field [:post-blog]])
        user @(rf/subscribe [:user/active-user])
        editing @(rf/subscribe [:blog/state [:editing]])]
    [:section.blog.blog-new-post
     [:h2 "Write blog post"]
     [:br]

     [ui/input-text
      :placeholder "Title"
      :path [:form-field [:post-blog :title]]
      :on-change #(rf/dispatch-sync [:form-field [:post-blog :title] %])]
     
     [ui/input-text
      :placeholder "Tags"
      :path [:form-field [:post-blog :tags]]
      :on-change #(rf/dispatch-sync [:form-field [:post-blog :tags] %])]
     
     [ui/input-text :input-type :textarea
      :placeholder "Text (markdown)"
      :height "40vh"
      :min-rows 6
      :width "100%"
      :path [:form-field [:post-blog :text]]
      :on-change #(rf/dispatch-sync [:form-field [:post-blog :text] %])]
     
     [:br]
     [ui/button "Save draft" :save-blog-draft] ;should save to firebase etc.
     [ui/button "Highlight code" :highlight-blog-code
      :action #(rf/dispatch [:run-highlighter!])]
     [:br]
     [:section.blog-post-preview
      [preview-blog input]]
     
     [:section
      [ui/button "Submit" :post-new-blog
       :action #(do (rf/dispatch [:blog/submit
                                  (merge {:user user} input)
                                  editing])
                    (rf/dispatch [:common/navigate! :blog]))]]]))
(defn tags-list [{:keys [id tags] :as post}]
  (when (pos? (count tags))
    [:div.blog-post-tags
     (doall (for [tag (string/split tags " ")]
              ^{:key (str "blog-post-" id "-category-" tag)}
              [:span tag]))]))

(defn make-link [path]
  @(rf/subscribe [:href :blog-post {:permalink path}]))

(defn blog-post "Towards a bloggy blag. Think float insets and stuff and, well md mostly heh"
  [{:keys [id ts user title text permalink comments] :as blog-post}]
  (let [user @(rf/subscribe [:user/user user])
        back? @(rf/subscribe [:history/popped?])]
    [ui/appear-anon (if back? "" "zoom-x")
     [:section.blog-post
     {:ref #(when % (rf/dispatch [:run-highlighter! %]))}
     [:div.flex.blog-post-header
      [ui/user-avatar user "blog-user-avatar"]
      [:div.blog-post-header-main
       [:a {:href (make-link (or permalink id))}
         [:h1.blog-post-title title]]
       [ui/appear-anon  "slide-in" #_"opacity"
        [posted-by id user ts]]
       (when (= (:id user) (:id @(rf/subscribe [:user/active-user])))
         [:button.noborder.nomargin
          {:on-click #(rf/dispatch [:blog/edit-post blog-post])}
          [:i.fa.fa-edit] ])
       [tags-list blog-post]]]
     [:br]
     ; [a custom sticky mini "how far youve scrolled bar" on right?]
     [:div.blog-post-text [ui/md->div text]]
     [:br] [:br]
     [ui/appear-anon "zoom-y" [comments-section blog-post]]]]))

(defn blog-single-post []
  (let [post (or @(rf/subscribe [:blog/post
                                  @(rf/subscribe [:blog/state [:current-post-id]])])
                  {:title "Not found" :text "This page doesn't exist. Authorities have been notified*"
                   :comments {:0 {:title "jk" :text "*Not yet implemented"}}})]
    [blog-container
     [:<>
     [blog-post post]
     (when (:id post)
       [:div.blog-prev-next-links
        (when-let [post @(rf/subscribe [:blog/adjacent-post :prev (:id post)])]
          [:a {:href (make-link (or (:permalink post) (:id post)))}
           [:span [:i.fa.fa-chevron-left] " " (:title post)]])
        (when-let [post @(rf/subscribe [:blog/adjacent-post :next (:id post)])]
          [:a {:href (make-link (or (:permalink post) (:id post)))}
           [:span (:title post) " " [:i.fa.fa-chevron-right]]])]) ]]))

(defn blog-archive "List of all posts with headlines etc. Maybe for a sidebar." []
  (let [posts @(rf/subscribe [:blog/post-feed])]
    [blog-container
     [:div.blog-archive
      [:h2 {:style {:text-align :center}} "All posts"] [:br] [:br]
      ; make bit of month(?) at least year, indicators.
      ; which means we need to pre-sort+split
     (for [{:keys [id ts user title text permalink] :as post} posts] ^{:key (str "blog-archive-" (:id post))}
       [:div.blog-archive-post
        [:a {:href (make-link (or permalink id))}
            [:h2 title]] ;should be link
        [posted-by id @(rf/subscribe [:user/user user]) ts]
        (when (pos? (count (:comments post)))
          [:span {:style {:font-size "0.7em"}} [util/pluralize (count (:comments post)) "comment"]])
        [tags-list blog-post]
        [:div (->> text string/split-lines (take 2) (string/join " ") ui/md->div)] ])]]))

(defn blog-tag-cloud "Tin"
  [])

(defn blog-tag-view "View posts filed with tag"
  [])

(defn blog-intros-view "Headline and a paragraph, many on each page."
  [])

(defn blog-nav "Blog navigation buttons"
  [total-posts current-idx posts-per-page]
  (let [nav-btn (fn [nav label & [attrs]]
                  [:a {:href @(rf/subscribe [:href :blog-page {:nr nav}])}
                   [:button.blog-btn.blog-nav-btn.topborder
                    attrs
                    label]])
        nav-idxs (doall (for [i (range 1 (inc (/ total-posts posts-per-page)))] ^{:key (str "blog-nav-btn-" i)}
                          [nav-btn i i (when (= i (inc current-idx))
                                         {:class "current"})]))
        back-btn (when-let [idx @(rf/subscribe [:blog/page-index-for-nav-action :prev])]
                   [nav-btn idx [:i.fa.fa-chevron-left]])
        fwd-btn (when-let [idx @(rf/subscribe [:blog/page-index-for-nav-action :next])]
                  [nav-btn idx [:i.fa.fa-chevron-right]])]
    [:div.blog-nav.center-content
      back-btn nav-idxs fwd-btn]))

(defn blog-feed "all the blogs. Should be called" []
  (when-let [total @(rf/subscribe [:blog/count])]
   (let [user @(rf/subscribe [:user/active-user])
        per-page (min total @(rf/subscribe [:blog/posts-per-page]))
        idx @(rf/subscribe [:blog/nav-page])
        posts @(rf/subscribe [:blog/posts-for-page idx per-page]) ]
    (when (pos? total)
      [:<>
       (doall (for [post posts] ^{:key (str "blog-post-" (:id post))}
                [blog-post post]))

       [blog-nav total idx per-page]]))))

(defn blog-container
  [section]
  [:section.blog.fullwide.noborder ;then chuck flip-move on eeet. or just same slide nav thing
   (if section
     section
     [:h1.center-content [ui/loading-spinner true]])
   [:div.flex.center-content
    (when (some #{(:id @(rf/subscribe [:user/active-user]))}
                (:bloggers @(rf/subscribe [:<-store :auth :roles])))
      [:a {:href @(rf/subscribe [:href :post-blog])}
       [:button.noborder [:i.fa.fa-feather-alt]]])
    [:a {:href @(rf/subscribe [:href :blog])}
     [:button.blog-btn.noborder
     "Home"]]
    [:a {:href @(rf/subscribe [:href :blog-archive])}
     [:button.blog-btn.noborder
     "Archive"]]]
   [:div.blog-powered-by.center-content
    [:p "Proudly powered by "
    [:a {:href "https://github.com/tolgraven/tolgraven"} "tolgrAVen"]
    [:i.fab.fa-github]] ]])


