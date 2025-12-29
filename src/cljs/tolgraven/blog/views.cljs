(ns tolgraven.blog.views
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [clojure.string :as string]
    [tolgraven.loader :as loader]
    [tolgraven.util :as util :refer [at]]
    [tolgraven.ui :as ui]))

(defn preview-comment "Live md preview I guess. Prob best just ratom not db thing..."
  [model]
  (let [{:keys [user title text]} @model]
    [:div.blog-comment-preview
     {:style {:min-height "7.35rem"}}
     (when title
       [:h3.blog-comment-title title])
     [ui/md->div text]]))

(declare add-comment)
(declare blog-container)

(defn posted-by "Get details of blog/comment..."
  [id user ts score]
  (let [user (if (string? user) ; user-id, not user-map
               @(rf/subscribe [:user/user user])
               user)
        username [:em.blog-user 
                  (if-let [username (:name user)]
                    username
                    "anon")]
        by (str "posted by ")
        ts (util/timestamp ts)]
    [:span.blog-info
     username
    (when (some #{(:id user)} (:admins @(rf/subscribe [:<-store :auth :roles])))
      [:span {:style {:font-size "80%"}}
       "admin"])
     [:span ts]
     (when-not (= 0 score)
       [:span (cond (pos? score) "+"
                    (neg? score) "")
        score])])) ;todo both score and upvote should fade in next to reply btn. but iffy now cause it's absolute etc

(defn add-comment-btn "Seemed like a good idea to swap button for input field when pressed but yeah, no..."
  [parent-path kind]
  (let [adding-comment? @(rf/subscribe [:comments/adding? parent-path])
        attrs {:on-click
               #(rf/dispatch
                 [:blog/adding-comment parent-path (not adding-comment?)])}]
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

(defn vote-btn [user active-user path vote]
  (when active-user
    (let [voted @(rf/subscribe [:blog/state [:voted path]])] ; obviously needs to be firestore sub. but also local debounce
      [:button.blog-btn.blog-comment-vote-btn
       {:class (if (= vote voted)
                 "noborder"
                 (case vote :up "topborder" :down "bottomborder"))
        :disabled (when (= vote voted) true)
        :on-click #(rf/dispatch [:blog/comment-vote 
                                 user active-user path vote])}
       (case vote :up "+" :down "-")])))

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
                    (rf/dispatch [:blog/expand-comment-thread path true])
                    (doseq [[k post] comments]
                      (rf/dispatch [:blog/expand-comment-thread
                                    (conj path (:id post))
                                    true])))}
       [:div.blog-comment-border]
       [:section.blog-comment.blog-comment-collapsed-placeholder
        (util/pluralize (count comments) " hidden reply")]])))

(defn comment-post "A comment, and any children, rendered by recursion, deferring doing much if optional arg visible? is false"
  [path {:keys [id seq-id ts user title text score] :as post} & [visible?]]
  (let [showing? (r/atom false) ; start false to avoid even temp initing what's not visible. Bit confusing since will usually be immediately reset to true.
        lines (string/split-lines text)
        full? (r/atom nil) ; nil means uninited/not relevant, false unexpanded...
        height (atom 0)
        show-expand-to-full? (r/atom false)
        is-preview? (= [:new-comment] path)
        comments (if (not is-preview?)
                   (rf/subscribe [:comments/for-q-flat (first path) (when (<= 2 (count path))
                                                                      (last path))])
                   (atom nil))
        ref-fn #(when %
                  (reset! height (.-clientHeight %))
                  (when (> (util/px-to-rem @height) 24)
                    (reset! full? false))) ]
    (fn [path {:keys [id seq-id ts user title text score] :as post} & [visible?]]
      (if (and visible? (not @visible?))
        (js/setTimeout (fn [] (reset! showing? false)) 1000) ; if previously visible, only remove from dom/subs once anim complete
        (reset! showing? true))
      
      (when @showing? ; also means could defer fetches for like immensely big ass threads. first needs rework to use only id from blog not entire body... much needed anyways
        (let [active-user @(rf/subscribe [:user/active-user])
              user @(rf/subscribe [:user/user user])
              expanded? (rf/subscribe [:comments/thread-expanded? path])]
          [:<>
           [:div.flex.blog-comment-around
            [:div.blog-comment-border
             {:style {:cursor (if (and @expanded? @comments)
                                "zoom-out"
                                (when @comments "zoom-in"))
                      :background-color (:bg-color user)
                      :opacity (if is-preview? 0.5 1.0)} ; somehow doesnt fly, why? missing a deg in gen
              :on-click #(when @comments
                           (rf/dispatch [:blog/expand-comment-thread path
                                         (not @expanded?)]))}]
            [:section.blog-comment
             {:class (when @full?
                       "blog-comment-full")
              :style (when is-preview? {:background-color "var(--bg-2-2)"
                                        :opacity 0.8})
              :ref ref-fn}
             
             [:div
              [loader/<lazy> {:module :user, :view :avatar} user]

              [:div.blog-comment-main
               [:h4.blog-comment-title title]
               [posted-by id user ts score]
               (when (not= active-user user)
                 [:span.blog-comment-vote [vote-btn user active-user path :up]
                                          [vote-btn user active-user path :down]])
               [:div.blog-comment-text
                {:style {:filter (when (neg? score)
                                   (str "brightness(calc(1 + "
                                        (max -0.7 (* 0.1 score)) "))"))}}
                [ui/md->div text]]]

             [:div.blog-comment-actions
               (when (= active-user user)
                 [edit-comment (conj path id)])
               (when active-user
                 [add-comment-btn path :reply])]]
             
             [:div.blog-comment-expansion
              (when (false? @full?)
                [:<>
                 [ui/appear-anon "opacity"
                  [:div.fade-to-black.bottom
                  ; {;:style {:opacity (if @show-expand-to-full? 0.5 1.0)} ; wont animate so useless fix or nuke
                  {:style {:z-index "0"}
                   :on-mouse-over #(reset! show-expand-to-full? true)
                   :on-mouse-leave #(js/setTimeout (fn []
                                                     (reset! show-expand-to-full? false))
                                                   2000)}]]
                 (when @show-expand-to-full? ; prob always show tbh...
                   [:button.blog-comment-view-full-btn
                    {:on-click #(reset! full? true)}
                    [:i.fa.fa-angle-down]])])
              (when @full?
                [:button.blog-comment-view-full-btn.blog-comment-view-less-btn
                 {:on-click #(reset! full? false)}
                 [:i.fa.fa-angle-up]])]]] 
           
           (when (and active-user
                      @(rf/subscribe [:comments/adding? path]))
             [add-comment path])

           (when @comments ;replies
             [:div.blog-comment-reply-outer
              [:div.blog-comment-reply
               {:class (when-not @expanded?
                         "collapsed")}
               (doall (for [[k post] (into (sorted-map) @comments)]
                        ^{:key (get-id-str (conj path (:id post)))}
                        [ui/appear-anon "slide-behind"
                         [comment-post (conj path (:id post)) post expanded?]] ))]
              (when-not @expanded?
                [collapsed-reply-view path id @comments])])])))))


(defn comments-section "Comments section!"
  [{:keys [id] :as blog-post}]
  (let [comments (vals @(rf/subscribe [:comments/for-q-flat id]))
        expanded? @(rf/subscribe [:blog/state [:comments-expanded id]])
        amount-show-collapsed 4
        amount-str (util/pluralize (count comments) "comment")]
    [:section.blog-comments
     (if (<= (count comments) amount-show-collapsed) ;only show a few comments unless expanded
       [:h6.bottomborder amount-str]
       [:button.blog-btn.blog-collapse-btn.nomargin.bottomborder
        {:on-click #(rf/dispatch [:blog/state [:comments-expanded id] (not expanded?)])}
        (if-not expanded? (str "Show all " amount-str) "Collapse")])

     (when (seq comments)
       [:div.blog-comments-inner
        {:class (when-not expanded? "collapsed")}
        (doall (for [comment (if expanded?
                               comments
                               (take amount-show-collapsed comments))
                     :let [path [(:id blog-post) (:id comment)]]]
                 ^{:key (get-id-str path)}
                 [comment-post path comment]))])

     (when @(rf/subscribe [:user/active-user])
       [add-comment-btn [id] :comment]) ;new comment button
     [add-comment [id]]]))


(defn add-comment "Post http or do a gql mutation, yada yada"
  [parent-path]
  (let [adding-comment? (rf/subscribe [:comments/adding? parent-path])
        editing (rf/subscribe [:blog/state [:editing-comment parent-path]]) ;XXX would break when multiple replies to same parent
        model (rf/subscribe [:form-field [:write-comment parent-path]]) ;should use db tho, no good accidental nav and lose shit
        input-valid? (fn [input]
                       (pos? (count (:text input))))
        preview? (r/atom false)
        box (fn [k kind & {:keys [style ui-name]}]
              (let [id (str "blog-adding-comment-" kind)
                    this (r/atom nil) ]
                (fn [k kind & {:keys [style ui-name]}]
                  (let [height (when (= kind :textarea)
                               {:min-height (when @this
                                              (-> @model :text ;sadly linebreak doesnt count so only expands once text on newline.
                                                  (str "-")
                                                  string/split-lines
                                                  count (* 1.15) (+ 2)
                                                  (->>
                                                   (util/em->px id))
                                                  (str "px")))})]
              [kind
               {:id id
                :class (str "blog-adding-comment-textbox")
                :type :textbox
                :ref #(when % (reset! this %))
                :value (get @model k)
                :name (or ui-name (name k))
                :placeholder (string/capitalize (or ui-name (name k)))
                :style (merge style
                              height)
                :on-change (fn [e]
                             (let [new-val (-> e .-target .-value)]
                               (rf/dispatch-sync [:form-field [:write-comment parent-path k] new-val])))
                :on-blur #(rf/dispatch-sync [:form-field [:write-comment parent-path k] (get @model k) :blur])}]))))
        submit-btn (fn []
                     [:button.blog-btn.noborder
                      {:class    (when (input-valid? @model) "topborder")
                       :disabled (when-not (input-valid? @model) true)
                       :on-click (fn [_]
                                   (when (input-valid? @model)
                                     (rf/dispatch [:blog/adding-comment parent-path nil])
                                     (rf/dispatch [:blog/comment-submit parent-path @model @editing])
                                     (rf/dispatch [:form-field [:write-comment parent-path] nil :blur])))}
                      "Submit"])
        valid-bg {:background-color "var(--bg-3-2)"}] ; tho stashing half-written in localstorage is p awesome when done. so db evt}]] ; tho stashing half-written in localstorage is p awesome when done. so db evt
     (fn [parent-path] ; needed or recreates to empty when swapped out
       (when @adding-comment?
         [:div.blog-comment-reply-outer
          [:div.blog-comment-reply
           [:div.blog-adding-comment
          #_[:button.blog-btn
           {:on-mouse-over #(reset! preview? true)
            :on-mouse-leave #(reset! preview? false)}
           "Preview"] #_[:br]
          #_(if @preview?
            [ui/appear-anon "opacity fast"
             [preview-comment model]]
            [ui/appear-anon "opacity fast"
             [:<>
             [box :title :input :style valid-bg :ui-name "Title (optional)"]
             [box :text :textarea :ui-name "Comment"]]])
          
          [comment-post [:new-comment] {:user @(rf/subscribe [:user/active-user])
                                        :title (get @model :title)
                                        :text (get @model :text)}]
          [ui/input-text-styled :model model
           :on-change (fn [v]
                        (rf/dispatch-sync [:form-field [:write-comment parent-path :text] v]))]
          ; [box :text :textarea :ui-name "Comment" :style {:opacity 0.1
          ;                                                 :z-index 10}]
          [submit-btn]
          [add-comment-btn parent-path :cancel]]]]))))

     ; :on-key-up (fn [e] (when (= "Alt-Enter-however-written" (.-key e)) (submit)))
; not here but whatever: thing from MYH site where heading slots into header

(defn preview-blog "Render new post preview"
  [{:keys [title text]}]
  [:div
    [:h2.blog-post-title title]
    [:br]
    [ui/md->div text]])

(defn post-blog "Render post-making ui" [] ; XXX move this and similar to own file...
  (let [input @(rf/subscribe [:form-field [:post-blog]])
        user @(rf/subscribe [:user/active-user])
        editing @(rf/subscribe [:blog/state [:editing]])
        new-id @(rf/subscribe [:blog/get-new-post-id])]
    [:section.blog.blog-new-post
     [:h2 "Write blog post"]
     [:br]

     [ui/input-text
      :placeholder "Title"
      :path [:form-field [:post-blog :title]]]
     
     [ui/input-text
      :placeholder "Tags"
      :path [:form-field [:post-blog :tags]]]
     
     [ui/input-text :input-type :textarea
      :placeholder "Text (markdown)"
      :height "40vh"
      :min-rows 6
      :width "100%"
      :path [:form-field [:post-blog :text]]]
     
     [ui/button "Save draft" :save-blog-draft] ;should save to firebase etc. Really just have an :unpublished true flag yeah.
     [ui/button "Highlight code" :highlight-blog-code
      :action #(rf/dispatch [:run-highlighter!])]
     
     [:br]
     [:section.blog-post-preview
      [preview-blog input]]
     
     [:section
      [ui/button "Submit" :post-new-blog
       :action #(do (rf/dispatch [:blog/submit
                                  (merge {:user user} input)
                                  editing
                                  new-id])
                    (rf/dispatch [:common/navigate! :blog]))]
      [:button {:on-click #(rf/dispatch [:common/navigate! :blog])} ; triggers controller hence cleanup
       [:label "Cancel"]]]]))

(defn tags-list [{:keys [id tags] :as post}]
  (when (pos? (count tags))
    [:div.blog-post-tags
     (doall (for [tag (string/split tags " ")]
              ^{:key (str "blog-post-" id "-category-" tag)}
              [:span [:a.blog-tag-link
                      {:href @(rf/subscribe [:href :blog-tag {:tag tag}])}
                      tag]]))]))


(defn blog-post "Towards a bloggy blag. Think float insets and stuff and, well md mostly heh"
  [{:keys [id ts user title text permalink comments] :as post}]
  (if-not text
   [ui/loading-spinner true :massive] ; ideally some placeholder flashing textish
   (let [user @(rf/subscribe [:user/user user])
         back? @(rf/subscribe [:history/back-nav-from-external?])]
    [ui/appear-anon (if back? "" "zoom-x")
     [:section.blog-post
      {:ref #(rf/dispatch [:run-highlighter!])}
      
     [:div.flex.blog-post-header
      [ui/appear-anon (if back? "" "zoom slower")
       [loader/<lazy> {:module :user, :view :avatar} user "blog-user-avatar"]]
      [:div.blog-post-header-main
       [:a {:href @(rf/subscribe [:blog/permalink-for-path (or permalink id)])}
         [:h1.blog-post-title title ]]
       [ui/appear-anon  (if back? "" "slide-in")
        [posted-by id user ts]]
       [:div.flex
        [tags-list post]
        (when (= (:id user) (:id @(rf/subscribe [:user/active-user])))
          [:button.noborder.nomargin
           {:on-click #(rf/dispatch [:blog/edit-post post])}
           [:i.fa.fa-edit] ])]]]
     ; [a custom sticky mini "how far youve scrolled bar" on right?]
     [:div.blog-post-text [ui/md->div text]]
     [ui/appear-anon (if back? "" "zoom-y")
      [comments-section post]]]])))

(defn blog-single-post []
  (let [post @(rf/subscribe [:blog/post
                             @(rf/subscribe [:blog/state [:current-post-id]])])]
   [blog-container
    [:<>
     [ui/loading-spinner (r/atom (not post)) :massive] ; would want a flashing skeletor instead
     ; OTHER IDEA FOR LOADING NAV:
     ; start with flashing text
     ; then blur it <- how figure out when to kick off? can't do it when loaded, too slow
     ; replace with post BUT VERY BLURRED quickly getting not blurry
     ;
     ; or just blur previous on nav then deblur-fade in new post...
     [blog-post post]
     (when (:id post)
       [:div.blog-prev-next-links
        {:ref #(rf/dispatch [:common/set-title (when % (:title post))])} ; otherwise nil -> resets
        (when-let [id @(rf/subscribe [:blog/adjacent-post-id :prev (:id post)])]
          (let [post @(rf/subscribe [:blog/post id])]
           [:a {:href @(rf/subscribe  [:blog/permalink-for-path  (or (:permalink post) (:id post))])}
            [:span [:i.fa.fa-chevron-left] " " (:title post)]]))
        (when-let [id @(rf/subscribe [:blog/adjacent-post-id :next (:id post)])]
          (let [post @(rf/subscribe [:blog/post id])]
            [:a {:href @(rf/subscribe [:blog/permalink-for-path  (or (:permalink post) (:id post))])}
            [:span (:title post) " " [:i.fa.fa-chevron-right]]]))]) ]]))

(defn blog-archive "List of all posts with headlines etc. Maybe for a sidebar." []
  (let [posts @(rf/subscribe [:blog/post-feed])]
    [blog-container
     [:div.blog-archive
      [:h2 {:style {:text-align :center}} "All posts"] [:br] [:br]
      ; make bit of month(?) at least year, indicators.
      ; which means we need to pre-sort+split
     (doall (for [{:keys [id ts user title text permalink] :as post} posts] ^{:key (str "blog-archive-" (:id post))}
       [:div.blog-archive-post
        [:a {:href @(rf/subscribe [:blog/permalink-for-path (or permalink id)])}
            [:h2 title]] ;should be link
        [posted-by id @(rf/subscribe [:user/user user]) ts]
        (when (pos? (count (:comments post)))
          [:span
           {:style {:font-size "0.7em"
                    :filter "brightness(0.8)"}}
           [util/pluralize (count (:comments post)) "comment"]])
        [tags-list post]
        [:div {:style {:padding-top "0.4em"
                       :padding-bottom "var(--space)"
                       :font-size "0.9em"}}
         [ui/md->div @(rf/subscribe [:blog/post-preview id])]] ]))]]))


(defn blog-tag-view "View posts filed with tag"
  []
  (when-let [tag @(rf/subscribe [:blog/state [:viewing-tag]])]
    [blog-container
     [:div.blog-posts-with-tag
      [:h2 {:style {:text-align :center}}
       "Posts tagged " [:span.blog-post-tags
                        [:span tag]]]
      (for [{:keys [id ts user title text permalink] :as post} @(rf/subscribe [:blog/posts-with-tag tag])]
        ^{:key (str "blog-with-tag-" (:id post))}
        [blog-post post])]]))

(defn blog-tag-cloud "Tin"
  []
  (let [tags @(rf/subscribe [:blog/all-tags])]
    [:div.blog-post-tags.flex.center-content
     [:p "Tags "]
     [:div.flex.center-content
      (doall (for [tag tags]
              ^{:key (str "blog-tag-" tag)}
              [:span [:a.blog-tag-link
                      {:href @(rf/subscribe [:href :blog-tag {:tag tag}])}
                      tag]]))]]))

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
        posts @(rf/subscribe [:blog/ids-for-page idx per-page]) ]
    (when (pos? total)
      [:<>
       (doall (for [id posts] ^{:key (str "blog-post-" id)}
                [blog-post @(rf/subscribe [:blog/post id])]))

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
      [:a {:href @(rf/subscribe [:href :post-blog])
           :title "Post blog"}
       [:button.noborder [:i.fa.fa-feather-alt]]])
    
    [:a {:href @(rf/subscribe [:href :blog])}
     [:button.blog-btn.noborder
     "Home"]]
    [:a {:href @(rf/subscribe [:href :blog-archive])}
     [:button.blog-btn.noborder
     "Archive"]]]
   
    [blog-tag-cloud]
    
   [:div.blog-powered-by.center-content
    [:p "Proudly powered by "
    [:a {:href "https://github.com/tolgraven/tolgraven"} "tolgrAVen"]
    [:i.fab.fa-github]]]])


(defn blog-page []
  [ui/with-heading [:blog :heading] [blog-container [blog-feed]]])
(defn post-blog-page [] ; how nicely set is-personal for this but also unset etc yada
  [ui/with-heading [:blog :heading] [post-blog]])
(defn blog-archive-page []
  [ui/with-heading [:blog :heading] [blog-archive]])
(defn blog-tag-page []
  [ui/with-heading [:blog :heading] [blog-tag-view]])
(defn blog-post-page []
  [ui/with-heading [:blog :heading] [blog-single-post]])

