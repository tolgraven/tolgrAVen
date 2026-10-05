(ns tolgraven.blog.views
  (:require
    [reagent.core :as r]
    [tolgraven.components.timer :as timer]
    [tolgraven.blog.model :as model]
    [tolgraven.blog.data :as data]
    [tolgraven.blog.comments :as comments]
    [tolgraven.react :as rf]
    [clojure.string :as string]
    [tolgraven.loader]
    [tolgraven.macros :as m :refer-macros [defc defpage]]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.component :as component]
    [tolgraven.component.loading :as loading]
    [tolgraven.link-preview.views :as link-preview]
    [tolgraven.util :as util]
    [tolgraven.ui :as ui]))

(defn- link-trust [user]
  (let [id (if (string? user) user (:id user))]
    (cond
      (nil? id) :untrusted
      @(rf/subscribe [:user/trusted? id]) :trusted
      :else :user)))


;; View inputs use one spec map; nested :post/:comment values keep domain data
;; separate from component options. Exported module views use the same contract.
(defc <preview-comment> "Render the current comment draft as Markdown."
  {:features [[:appear nil]]}
  [{:keys [model] :as spec}]
  (let [{:keys [user title text]} @model]
    [:div.blog-comment-preview
     {:style {:min-height "7.35rem"}}
     (when title
       [:h3.blog-comment-title title])
     [link-preview/<md> text {:trust (link-trust user)}]]))

(declare <add-comment>)
(declare <blog-container>)

(defc <posted-by> "Render author, timestamp, and optional score."
  {:features [[:appear nil]]}
  [{:keys [id user ts score] :or {score 0} :as spec}]
  (let [user @(rf/subscribe [:user/user user])
        username [:em.blog-user
                  (if-let [username (:name user)]
                    username
                    "anon")]
        ts @(rf/subscribe [:timestamp ts])]
    [:span.blog-info
     username
    (when @(rf/subscribe [:user/trusted? (:id user)])
      [:span {:style {:font-size "80%"}}
       "admin"])
     [:span ts]
     (when-not (= 0 score)
       [:span (cond (pos? score) "+"
                    (neg? score) "")
        score])])) ;todo both score and upvote should fade in next to reply btn. but iffy now cause it's absolute etc

(defc <add-comment-btn> "Open or cancel the comment editor."
  [{:keys [parent-path kind] :as spec}]
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
                  {:on-click #(rf/dispatch [:blog/cancel-comment parent-path])}
                  "Cancel"]))))

(defc <edit-comment>
  [{:keys [path comment] :as spec}]
  [:button.blog-btn.blog-comment-edit-btn.noborder
   {:on-click #(rf/dispatch [:blog/edit-comment path comment])}
   [:i.fa.fa-edit]])

(defc <delete-comment> ; Unfinished delete UI; retain for later implementation.
  [{:keys [path] :as spec}]
  [:div "whua"])

(defn- get-id-str [path]
  (reduce (fn [s i]
            (str s "-" i))
          (str "blog-post-" (first path) "-comment")
          (rest path)))


(defc <vote-btn> [{:keys [user active-user path vote] :as spec}]
  (when active-user
    (let [voted @(rf/subscribe [:blog/vote path])]
      [:button.blog-btn.blog-comment-vote-btn
       {:class (if (= vote voted)
                 "noborder"
                 (case vote :up "topborder" :down "bottomborder"))
        :disabled @(rf/subscribe [:state [:supabase-writes [:vote (str (last path))]]])
        :on-click #(rf/dispatch [:blog/comment-vote
                                 user active-user path vote])}
       (case vote :up "+" :down "-")])))

(defc <collapsed-reply-view>
  {:features [[:appear "zoom-y"]]}
  [{:keys [path comments reply-count loading?] :as spec}]
  [:div.blog-comment-reply.flex
   {:style {:cursor "zoom-in"
            :max-height "3rem"}
    :on-click #(if-let [expand! (:expand! spec)] (expand!)
                   (rf/dispatch [:blog/expand-comment-thread path true]))}
   [:div.blog-comment-border]
   [:section.blog-comment.blog-comment-collapsed-placeholder
    {:aria-busy (boolean loading?)}
    (if loading? "Loading replies…" (util/pluralize (or reply-count (count comments)) " hidden reply"))]])

(defc <comment-frame> {:features [:props [:appear nil]]} [{:keys [children] :as spec}]
  (into [:div.flex.blog-comment-around] children))

(defc <comment-fade> {:features [:props [:appear "opacity"]]} [spec]
  [:div.fade-to-black.bottom {:style {:z-index "0"}}])

(defc <comment-post> "Render a comment and its replies; defer hidden threads until needed."
  {:features [:error-boundary]}
  [{:keys [path visible?] {:keys [id ts user title text score] :as post} :comment :as spec}]
  :let [*full? (r/atom nil) ; nil: not measured, false: truncated, true: expanded
        *interacted? (r/atom false)
        capture-height! (fn [element]
                          (when (and element (> (util/element-height-rem element) 24))
                            (reset! *full? false)))]
  (let [[show-expand? show-expand! hide-expand!] (timer/use-delayed-hide 2000)
        showing? (timer/use-delayed-visible? (or (nil? visible?) @visible?) 250)
        is-preview? (= [:new-comment] path)
        *expanded? (rf/subscribe [:comments/thread-expanded? path])
        *comments (when-not is-preview?
                    (rf/subscribe [:comments/visible-thread (first path) (last path)
                                   (boolean (and showing? @*expanded?
                                                 (or (nil? (:reply-count post)) (pos? (:reply-count post))))) path]))]
    (when showing?
      (let [active-user @(rf/subscribe [:user/active-user])
            user @(rf/subscribe [:user/user user])
            score (or score 0)
            *comments (or *comments (r/atom nil))
            replies? (or (seq @*comments) (pos? (:reply-count post 0)))]
          [:<>
           [<comment-frame> {:appear {:class (or (:appear spec) "zoom-y fast")
                                      :restore? (not (:enter? spec))
                                      :remember-key (when (nil? visible?) [:blog/comment path])}
                            :props {:style {:transition-delay (str (or (:stagger-ms spec) 0) "ms")
                                            "--comment-reveal-duration" (str (or (:motion-ms spec) 180) "ms")
                                            "--comment-reveal-delay" (str (or (:stagger-ms spec) 0) "ms")}}
                            :children [
            [:div.blog-comment-border
             {:style {:cursor (if (and @*expanded? replies?)
                                "zoom-out"
                                (when replies? "zoom-in"))
                      :background-color (:bg-color user)
                      :opacity (if is-preview? 0.5 1.0)}
              :on-click #(when replies?
                           (reset! *interacted? true)
                           (rf/dispatch [:blog/expand-comment-thread path
                                         (not @*expanded?)]))}]
            [:section.blog-comment
             {:class (when @*full?
                       "blog-comment-full")
              :data-link-trust (name (link-trust user))
              :style (when is-preview? {:background-color "var(--bg-2-2)"
                                        :opacity 0.8})
              :ref capture-height!}

             [:div
              (m/<> :user/avatar user)

              [:div.blog-comment-main
               [:h4.blog-comment-title title]
               [<posted-by> {:id id :user user :ts ts :score score}]
               (when (not= (:id active-user) (:id user))
                 [:span.blog-comment-vote [<vote-btn> {:user user :active-user active-user :path path :vote :up}]
                                          [<vote-btn> {:user user :active-user active-user :path path :vote :down}]])
               [:div.blog-comment-text
                {:style {:filter (when (neg? score)
                                   (str "brightness(calc(1 + "
                                        (max -0.7 (* 0.1 score)) "))"))}}
                [link-preview/<md> text
                 {:trust (link-trust user)}]]]

             [:div.blog-comment-actions
               (when (and active-user (= (:id active-user) (:id user)))
                 [<edit-comment> {:path path :comment post}])
               (when active-user
                 [<add-comment-btn> {:parent-path path :kind :reply}])]]

             [:div.blog-comment-expansion
              (when (false? @*full?)
                [:<>
                 [<comment-fade>
                  {:props {:on-mouse-over show-expand!
                           :on-mouse-leave hide-expand!}}]
                 (when show-expand?
                   [:button.blog-comment-view-full-btn
                    {:on-click #(reset! *full? true)}
                    [:i.fa.fa-angle-down]])])
              (when @*full?
                [:button.blog-comment-view-full-btn.blog-comment-view-less-btn
                 {:on-click #(reset! *full? false)}
                 [:i.fa.fa-angle-up]])]]]}]

           (when (and active-user
                      @(rf/subscribe [:comments/adding? path]))
             [<add-comment> {:parent-path path}])

           (when replies? ;replies
             [:div.blog-comment-reply-outer
              [:div.blog-comment-reply
               {:class (when-not @*expanded? "collapsed")
                :style {"--comment-reveal-duration" (str (comments/reveal-duration-ms (count @*comments)) "ms")}}
               (doall (for [[index post] (map-indexed vector (sort-by :ts (vals @*comments)))]
                        ^{:key (get-id-str (conj path (:id post)))}
                        [<comment-post> {:path (conj path (:id post)) :comment post
                                         :enter? (or (:enter? spec) @*interacted?)
                                         :visible? *expanded? :appear "slide-behind"
                                         :motion-ms (comments/reveal-duration-ms (count @*comments))
                                         :stagger-ms (min 36 (+ (or (:stagger-ms spec) 0) 12 (* 12 index)))}] ))]
              (when (or (not @*expanded?) (nil? @*comments))
                [<collapsed-reply-view> {:path path :comments @*comments :reply-count (:reply-count post)
                                         :expand! (fn [] (reset! *interacted? true)
                                                    (rf/dispatch [:blog/expand-comment-thread path true]))
                                         :loading? (and @*expanded? (nil? @*comments))}])])]))))

(defc <comments-section> "Comments section!"
  {:features [[:appear "zoom-y"]]}
  [{{:keys [id] :as post} :post :as spec}]
  (let [{:keys [records loading? more?]} @(rf/subscribe [:comments/root-page id])
        comments (->> (vals records) (sort-by (juxt :ts :id)) reverse)]
    [:section.blog-comments
     [:h6.bottomborder (str (util/pluralize (count comments) "comment") (when more? "+"))]
     (when (seq comments)
       [:div.blog-comments-inner
        (doall (for [[index comment] (map-indexed vector comments) :let [path [id (:id comment)]]]
                 ^{:key (get-id-str path)}
                 [<comment-post> {:path path :comment comment
                                  :stagger-ms (min 48 (* 12 index))}]))])
     (when more?
       [:button.blog-btn.blog-load-more
        {:disabled loading? :on-click #(rf/dispatch [:blog/load-more-comments id])}
        (if loading? "Loading comments…" "Load more comments")])

     (when @(rf/subscribe [:user/active-user])
       [<add-comment-btn> {:parent-path [id] :kind :comment}])
     [<add-comment> {:parent-path [id]}]]))


(defc <submit-comment-btn> [{:keys [parent-path model editing?] :as spec}]
  (let [valid? (pos? (count (:text model)))]
    [:button.blog-btn.noborder
     {:class (when valid? "topborder")
      :disabled (or (not valid?)
                    @(rf/subscribe [:state [:supabase-writes [:comment parent-path]]]))
      :on-click #(when valid?
                   (rf/dispatch [:blog/comment-submit parent-path model editing?]))}
     "Submit"]))

(defc <comment-input> "Retained experimental comment field, currently unused."
  [{:keys [parent-path field kind model style ui-name] :as spec}]
  :let [*element (r/atom nil)]
  (let [id (str "blog-adding-comment-" kind)
        height (when (= kind :textarea)
                 {:min-height (when @*element
                                (-> model :text
                                    (str "-")
                                    string/split-lines
                                    count (* 1.15) (+ 2)
                                    (->> (util/em->px id))
                                    (str "px")))})]
    [kind
     {:id id :class "blog-adding-comment-textbox" :type :textbox
      :ref #(when % (reset! *element %))
      :value (get model field)
      :name (or ui-name (name field))
      :placeholder (string/capitalize (or ui-name (name field)))
      :style (merge style height)
      :on-change #(rf/dispatch-sync [:form-field [:write-comment parent-path field]
                                    (-> % .-target .-value)])
      :on-blur #(rf/dispatch-sync [:form-field [:write-comment parent-path field]
                                  (get model field) :blur])}]))

(defc <add-comment> "Edit or submit a comment with a live preview."
  {:features [:error-boundary]}
  [{:keys [parent-path] :as spec}]
  :let [*preview? (r/atom false)]
  (when @(rf/subscribe [:comments/adding? parent-path])
    (let [*model (rf/subscribe [:form-field [:write-comment parent-path]])]
      [:div.blog-comment-reply-outer
       [:div.blog-comment-reply
        [:div.blog-adding-comment
         ;; Retain the alternative hover preview and custom-field experiment.
         #_[:button.blog-btn
            {:on-mouse-over #(reset! *preview? true)
             :on-mouse-leave #(reset! *preview? false)}
            "Preview"]
         #_(if @*preview?
             [<preview-comment> {:model *model :appear "opacity fast"}]
             [<comment-frame>
              {:appear "opacity fast"
               :children
               [[<comment-input> {:parent-path parent-path :field :title :kind :input
                                  :model @*model :style {:background-color "var(--bg-3-2)"}
                                  :ui-name "Title (optional)"}]
                [<comment-input> {:parent-path parent-path :field :text :kind :textarea
                                  :model @*model :ui-name "Comment"}]]}])
         [<comment-post> {:path [:new-comment]
                          :comment {:user @(rf/subscribe [:user/active-user])
                                    :title (:title @*model)
                                    :text (or (:text @*model) "")}}]
         [ui/<input-text-styled> :model *model
          :on-change #(rf/dispatch-sync [:form-field [:write-comment parent-path :text] %])]
         ;; The experimental field could use :style {:opacity 0.1 :z-index 10}.
         ;; Future shortcut: submit on Alt-Enter.
         [<submit-comment-btn> {:parent-path parent-path :model @*model
                                :editing? @(rf/subscribe [:blog/state [:editing-comment parent-path]])}]
         [<add-comment-btn> {:parent-path parent-path :kind :cancel}]]]])))

(defc <preview-blog> "Render new post preview"
  [{{:keys [title text]} :post :as spec}]
  [:div
    [:h2.blog-post-title title]
    [:br]
    [link-preview/<md> text
     {:trust (link-trust @(rf/subscribe [:user/active-user]))}]])

(defc <post-blog> "Render post-making ui"
  {:features [:error-boundary]}
  [] ; XXX move this and similar to own file...
  (let [input @(rf/subscribe [:form-field [:post-blog]])
        user @(rf/subscribe [:user/active-user])
        editing @(rf/subscribe [:blog/state [:editing]])]
    [:section.blog.blog-new-post
     [:h2 "Write blog post"]
     [:br]

     [ui/<input-text>
      :placeholder "Title"
      :path [:form-field [:post-blog :title]]]

     [ui/<input-text>
      :placeholder "Tags"
      :path [:form-field [:post-blog :tags]]]

     [ui/<input-text> :input-type :textarea
      :placeholder "Text (markdown)"
      :height "40vh"
      :min-rows 6
      :width "100%"
      :path [:form-field [:post-blog :text]]]

     [ui/<button> "Save draft" :save-blog-draft]
     [ui/<button> "Highlight code" :highlight-blog-code
      :action #(rf/dispatch [:run-highlighter!])]

     [:br]
     [:section.blog-post-preview
      [<preview-blog> {:post input}]]

     [:section
      [ui/<button> "Submit" :post-new-blog
       :action #(do (rf/dispatch [:blog/submit
                                  (merge {:user user} input)
                                  editing])
                    nil)]
      [:button {:on-click #(rf/dispatch [:common/navigate! :blog])} ; triggers controller hence cleanup
       [:label "Cancel"]]]]))

(defc <tag-link> [{:keys [tag]}]
  [:span [:a.blog-tag-link {:href @(rf/subscribe [:href :blog-tag {:tag tag}])} tag]])

(defc <tags-list> [{{:keys [id tags]} :post :as spec}]
  (when-let [tags (seq (model/tags tags))]
    [:div.blog-post-tags
     (for [tag (sort tags)]
       ^{:key (str "blog-post-" id "-category-" tag)}
       [<tag-link> {:tag tag}])]))

(defc <post-header> {:features [[:appear "zoom slower"]]} [{:keys [children] :as spec}]
  (into [:div.flex.blog-post-header] children))

(defc <post-content> "Render a loaded post with metadata and comments."
  {:features [:error-boundary [:appear "zoom-x"]]}
  [{{:keys [id ts user title text permalink comments] :as post} :post :as spec}]
  (let [user @(rf/subscribe [:user/user user])]
     [:section.blog-post
      {:data-link-trust (name (link-trust user))}

     [<post-header> {:appear {:class "zoom slower" :remember-key [:blog/post-header id]}
                    :children [
       (m/<> :user/avatar user "blog-user-avatar")
      [:div.blog-post-header-main
       [:a {:href @(rf/subscribe [:blog/permalink-for-path (or permalink id)])}
         [:h1.blog-post-title title ]]
       [<posted-by> {:id id :user user :ts ts
                     :appear {:class "slide-in" :remember-key [:blog/posted-by id]}}]
       [:div.flex
        [<tags-list> {:post post}]
        (when (= (:id user) (:id @(rf/subscribe [:user/active-user])))
          [:button.noborder.nomargin
           {:on-click #(rf/dispatch [:blog/edit-post post])}
           [:i.fa.fa-edit] ])]]]}]
     ; [a custom sticky mini "how far youve scrolled bar" on right?]
     [:div.blog-post-text
      [link-preview/<md> text
       {:id (str "blog-post-" id)
        :trust (link-trust user)}]]
     [<comments-section> {:post post :appear {:class "zoom-y" :remember-key [:blog/comments id]}}]]))

(defc <blog-post>
  {:loading-prefab :article :loading-tag :section.blog-post}
  [{:keys [id post] :as spec}]
  (cond
    (:text post) ^{:key (:id post)} [<post-content> (assoc spec :appear {:class "zoom-x" :remember-key [:blog/post (:id post)]})]
    (and id @(rf/subscribe [:blog/post-loaded? id])) [:p {:role "status"} "Post not found."]
    :else [loading/<query-fallback> (data/post-query id) [<loading>]]))

(defc <post-by-id> [{:keys [id]}]
  ;; Own the subscription in a render context, never inside a lazy parent for.
  [<blog-post> {:id id :post @(rf/subscribe [:blog/post id])}])

(defc <adjacent-post-link> [{:keys [direction post-id] :as spec}]
  (when-let [id @(rf/subscribe [:blog/adjacent-post-id direction post-id])]
    (let [{:keys [title permalink]} @(rf/subscribe [:blog/post-summary id])]
      [:a {:rel (name direction) :href @(rf/subscribe [:blog/permalink-for-path (or permalink id)])}
       [:span
        (when (= direction :prev) [:<> [:i.fa.fa-chevron-left] " "])
        title
        (when (= direction :next) [:<> " " [:i.fa.fa-chevron-right]])]])))

(defc <blog-single-post> []
  (let [id @(rf/subscribe [:blog/state [:current-post-id]])
        post @(rf/subscribe [:blog/post id])]
    [<blog-container>
     {:section
      [:<>
       ;; Future navigation experiment: keep the previous post blurred while
       ;; loading its replacement, or animate a skeleton into the new text.
       [<blog-post> {:id id :post post}]
       (when (:id post)
         [:div.blog-prev-next-links
          {:ref #(rf/dispatch [:common/set-title (when % (:title post))])}
          [<adjacent-post-link> {:direction :prev :post-id (:id post)}]
          [<adjacent-post-link> {:direction :next :post-id (:id post)}]])]}]))

(defc <archive-post> [{{:keys [id ts user title permalink comments] :as post} :post :as spec}]
  [:div.blog-archive-post
   [:a {:href @(rf/subscribe [:blog/permalink-for-path (or permalink id)])}
    [:h2 title]]
   [<posted-by> {:id id :user user :ts ts}]
   (when (seq comments)
     [:span {:style {:font-size "0.7em" :filter "brightness(0.8)"}}
      (util/pluralize (count comments) "comment")])
   [<tags-list> {:post post}]
   [:div {:style {:padding-top "0.4em" :padding-bottom "var(--space)" :font-size "0.9em"}}
    [link-preview/<md> @(rf/subscribe [:blog/post-preview id])
     {:trust (link-trust user)}]]])

(defc <blog-archive> "Render the post archive." []
  [<blog-container>
   {:section
    [:div.blog-archive
     [:h2 {:style {:text-align :center}} "All posts"] [:br] [:br]
     ;; Future grouping: month/year separators, based on publication time.
     (for [post @(rf/subscribe [:blog/post-feed])]
       ^{:key (str "blog-archive-" (:id post))}
       [<archive-post> {:post post}])]}])

(defc <blog-tag-view> "Render posts filed under the selected tag."
  {:loading-prefab :article :loading-tag :section.blog-post}
  []
  (when-let [tag @(rf/subscribe [:blog/state [:viewing-tag]])]
    [<blog-container>
     {:section
      [:div.blog-posts-with-tag
       [:h2 {:style {:text-align :center}}
        "Posts tagged " [:span.blog-post-tags [:span tag]]]
       (if-some [posts @(rf/subscribe [:blog/posts-with-tag tag])]
         (for [post posts]
           ^{:key (str "blog-with-tag-" (:id post))}
           [<blog-post> {:id (:id post) :post post}])
         [loading/<query-fallback> (data/tag-query tag) [<loading>]])]}]))

(defc <blog-tag-cloud> "Render all blog tags." []
  [:div.blog-post-tags.flex.center-content
   [:p "Tags "]
   [:div.flex.center-content
    (for [tag (sort @(rf/subscribe [:blog/all-tags]))]
      ^{:key (str "blog-tag-" tag)}
      [<tag-link> {:tag tag}])]])

(defc <blog-intros-view> "Headline and a paragraph, many on each page."
  []
  nil)

(defc <nav-btn> [{:keys [nav label props rel] :as spec}]
  [:a {:rel rel :href @(rf/subscribe [:href :blog-page {:nr nav}])}
   [:button.blog-btn.blog-nav-btn.topborder props label]])

(defc <blog-nav> "Blog navigation buttons"
  [{:keys [total-posts current-idx posts-per-page] :as spec}]
  (let [page-count (model/page-count total-posts posts-per-page)
        previous @(rf/subscribe [:blog/page-index-for-nav-action :prev])
        next-page @(rf/subscribe [:blog/page-index-for-nav-action :next])]
    [:div.blog-nav.center-content
     (when previous [<nav-btn> {:nav previous :rel "prev" :label [:i.fa.fa-chevron-left]}])
     (for [number (range 1 (inc page-count))]
       ^{:key (str "blog-nav-btn-" number)}
       [<nav-btn> {:nav number :label number
                   :props (when (= number (inc current-idx)) {:class "current"})}])
     (when next-page [<nav-btn> {:nav next-page :rel "next" :label [:i.fa.fa-chevron-right]}])]))

(defc <blog-feed> "Render the current database page of posts."
  {:loading-prefab :article :loading-tag :section.blog-post}
  []
  (let [total @(rf/subscribe [:blog/count])
        size @(rf/subscribe [:blog/posts-per-page])
        index @(rf/subscribe [:blog/nav-page])
        posts @(rf/subscribe [:blog/posts-for-page index size])]
    [:<>
     (if (some? posts)
       (for [post posts]
         ^{:key (str "blog-post-" (:id post))}
         [<blog-post> {:id (:id post) :post post}])
       [loading/<query-fallback> (data/page-query index size) [<loading>]])
     (when (pos? total)
       [<blog-nav> {:total-posts total :current-idx index :posts-per-page size}])]))

(defc <blog-container>
  [{:keys [section] :as spec}]
  [:section.blog.fullwide.noborder
   (if section
     section
     [loading/<spinner>])
   [:div.flex.center-content
    (when (or @(rf/subscribe [:user/has-role? :bloggers]) @(rf/subscribe [:user/has-role? :admins]))
      [:a {:href @(rf/subscribe [:href :new-post])
           :title "Post blog"}
       [:button.noborder [:i.fa.fa-feather-alt]]])

    [:a {:href @(rf/subscribe [:href :blog])}
     [:button.blog-btn.noborder
     "Home"]]
    [:a {:href @(rf/subscribe [:href :blog-archive])}
     [:button.blog-btn.noborder
     "Archive"]]]

    [<blog-tag-cloud>]

   [:div.blog-powered-by.center-content
    [:p "Proudly powered by "
    [:a {:href "https://github.com/tolgraven/tolgraven"} "tolgrAVen"]
    [:i.fab.fa-github]]]])


(defpage <blog-page>
  {:depends (get content-contract/module-dependencies :blog)}
  []
  ;; One outer component identity preserves the heading across blog routes. Only
  ;; the selected content changes; SSR and SPA use the same route subscription.
  (let [page (get-in @(rf/subscribe [:common/route]) [:data :page])]
    [ui/<with-heading> [:blog :heading]
     (case page
       :post [<blog-single-post>]
       :tag [<blog-tag-view>]
       :archive [<blog-archive>]
       :new-post [<post-blog>]
       [<blog-container> {:section [<blog-feed>]}])]))
