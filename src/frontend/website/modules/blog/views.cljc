(ns website.modules.blog.views
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  #?(:cljs (:require #?(:clj [website.build.site :refer [when-enabled]])
    [website.react :as r]
    [website.modules.blog.model :as model]
    [website.modules.blog.schema :as schema]
    [website.modules.blog.data :as data]
    #?(:feature/blog-comments [website.modules.blog.comment-views :as comment-views])
    [website.modules.blog.presentation :as presentation]

    [clojure.string :as string]
    [website.loader]
    [website.macros :as m :refer-macros [defc defpage]]
    [website.content.contract :as content-contract]
    [website.page :as page]
    [website.schema.common :as c]
    [website.component :as component]
    [website.component.loading :as loading]
    [website.util :as util]
    [website.components.ui.controls :as ui-controls]
    [website.components.ui.layout :as ui-layout] )))

#?(:cljs
   (do

;; View inputs use one spec map; nested :post/:comment values keep domain data
;; separate from component options. Exported module views use the same contract.


(declare <blog-container>)

(defc <tag-link> [{:keys [tag]} :- schema/tag-spec]
  [:span [:a.blog-tag-link {:href @(r/subscribe [:href :blog-tag {:tag tag}])} tag]])

(defc <tags-list> [{{:keys [id tags]} :post :as spec} :- schema/post-spec]
  (when-let [tags (seq (model/tags tags))]
    [:div.blog-post-tags
     (for [tag (sort tags)]
       ^{:key (str "blog-post-" id "-category-" tag)}
       [<tag-link> {:tag tag}])]))

(defc <post-header> {:features [[:appear "zoom slower"]]
                     :spec-schema schema/post-header-spec} [{:keys [children] :as spec}]
  (into [:div.flex.blog-post-header] children))

(defc <post-content> "Render a loaded post with metadata and comments."
  {:features [:error-boundary [:appear "zoom-x"]]}
  [{{:keys [id ts user title text permalink comments] :as post} :post :as spec} :- schema/post-spec]
  (let [user @(r/subscribe [:user/user user])]
     [:section.blog-post
      {:data-link-trust (name (presentation/link-trust user))}

     [<post-header> {:appear {:class "zoom slower" :remember-key [:blog/post-header id]}
                    :children [
       (m/<> :user/avatar user "blog-user-avatar")
      [:div.blog-post-header-main
       [:a {:href @(r/subscribe [:blog/permalink-for-path (or permalink id)])}
         [:h1.blog-post-title title ]]
       [presentation/<posted-by> {:id id :user user :ts ts
                     :appear {:class "slide-in" :remember-key [:blog/posted-by id]}}]
       [:div.flex
        [<tags-list> {:post post}]
        (when (= (:id user) (:id @(r/subscribe [:user/active-user])))
          [:button.noborder.nomargin
           {:on-click #(r/dispatch [:blog/edit-post post])}
           [:i.fa.fa-edit] ])]]]}]
     ; [a custom sticky mini "how far youve scrolled bar" on right?]
     [:div.blog-post-text
      [presentation/<markdown> text
       {:id (str "blog-post-" id)
        :trust (presentation/link-trust user)
        :image-assets (:image-assets post)}]]
     (when-enabled :blog :comments
       [comment-views/<comments-section> {:post post :appear {:class "zoom-y" :remember-key [:blog/comments id]}}])]))

(defc <blog-post>
  {:loading-prefab :article :loading-tag :section.blog-post}
  [{:keys [id post] :as spec}]
  (cond
    (:text post) ^{:key (:id post)} [<post-content> (assoc spec :appear {:class "zoom-x" :remember-key [:blog/post (:id post)]})]
    (and id @(r/subscribe [:blog/post-loaded? id])) [:p {:role "status"} "Post not found."]
    :else [loading/<query-fallback> (data/post-query id) [<loading>]]))

(defc <post-by-id> [{:keys [id]}]
  ;; Own the subscription in a render context, never inside a lazy parent for.
  [<blog-post> {:id id :post @(r/subscribe [:blog/post id])}])

(defc <adjacent-post-link> [{:keys [direction post-id] :as spec}]
  (when-let [id @(r/subscribe [:blog/adjacent-post-id direction post-id])]
    (let [{:keys [title permalink]} @(r/subscribe [:blog/post-summary id])]
      [:a {:rel (name direction) :href @(r/subscribe [:blog/permalink-for-path (or permalink id)])}
       [:span
        (when (= direction :prev) [:<> [:i.fa.fa-chevron-left] " "])
        title
        (when (= direction :next) [:<> " " [:i.fa.fa-chevron-right]])]])))

(defc <blog-single-post> []
  (let [id @(r/subscribe [:blog/state [:current-post-id]])
        post @(r/subscribe [:blog/post id])]
    [<blog-container>
     {:section
      [:<>
       ;; Future navigation experiment: keep the previous post blurred while
       ;; loading its replacement, or animate a skeleton into the new text.
       [<blog-post> {:id id :post post}]
       (when (:id post)
         [:div.blog-prev-next-links
          {:ref #(r/dispatch [:common/set-title (when % (:title post))])}
          [<adjacent-post-link> {:direction :prev :post-id (:id post)}]
          [<adjacent-post-link> {:direction :next :post-id (:id post)}]])]}]))

(defc <archive-post> [{{:keys [id ts user title permalink comments] :as post} :post :as spec}]
  [:div.blog-archive-post
   [:a {:href @(r/subscribe [:blog/permalink-for-path (or permalink id)])}
    [:h2 title]]
   [presentation/<posted-by> {:id id :user user :ts ts}]
   (when (seq comments)
     [:span {:style {:font-size "0.7em" :filter "brightness(0.8)"}}
      (util/pluralize (count comments) "comment")])
   [<tags-list> {:post post}]
   [:div {:style {:padding-top "0.4em" :padding-bottom "var(--space)" :font-size "0.9em"}}
    [presentation/<markdown> @(r/subscribe [:blog/post-preview id])
     {:trust (presentation/link-trust user)}]]])

(defc <blog-archive> "Render the post archive." []
  [<blog-container>
   {:section
    [:div.blog-archive
     [:h2 {:style {:text-align :center}} "All posts"] [:br] [:br]
     ;; Future grouping: month/year separators, based on publication time.
     (for [post @(r/subscribe [:blog/post-feed])]
       ^{:key (str "blog-archive-" (:id post))}
       [<archive-post> {:post post}])]}])

(defc <blog-tag-view> "Render posts filed under the selected tag."
  {:loading-prefab :article :loading-tag :section.blog-post}
  []
  (when-let [tag @(r/subscribe [:blog/state [:viewing-tag]])]
    [<blog-container>
     {:section
      [:div.blog-posts-with-tag
       [:h2 {:style {:text-align :center}}
        "Posts tagged " [:span.blog-post-tags [:span tag]]]
       (if-some [posts @(r/subscribe [:blog/posts-with-tag tag])]
         (for [post posts]
           ^{:key (str "blog-with-tag-" (:id post))}
           [<blog-post> {:id (:id post) :post post}])
         [loading/<query-fallback> (data/tag-query tag) [<loading>]])]}]))

(defc <blog-tag-cloud> "Render all blog tags." []
  [:div.blog-post-tags.flex.center-content
   [:p "Tags "]
   [:div.flex.center-content
    (for [tag (sort @(r/subscribe [:blog/all-tags]))]
      ^{:key (str "blog-tag-" tag)}
      [<tag-link> {:tag tag}])]])

(defc <blog-intros-view> "Headline and a paragraph, many on each page."
  []
  nil)

(defc <nav-btn> [{:keys [nav label props rel] :as spec}]
  [:a.button.blog-btn.blog-nav-btn.topborder
   (merge props {:rel rel
                 :href @(r/subscribe [:href :blog-page {:nr nav}])
                 :aria-label (case rel "prev" "Previous page" "next" "Next page" (str "Page " nav))
                 :aria-current (when (= "current" (:class props)) "page")})
   label])

(defc <blog-nav> "Blog navigation buttons"
  [{:keys [total-posts current-idx posts-per-page] :as spec}]
  (let [page-count (model/page-count total-posts posts-per-page)
        previous @(r/subscribe [:blog/page-index-for-nav-action :prev])
        next-page @(r/subscribe [:blog/page-index-for-nav-action :next])]
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
  (let [total @(r/subscribe [:blog/count])
        size @(r/subscribe [:blog/posts-per-page])
        index @(r/subscribe [:blog/nav-page])
        posts @(r/subscribe [:blog/posts-for-page index size])]
    [:<>
     (if (some? posts)
       (for [post posts]
         ^{:key (str "blog-post-" (:id post))}
         [<blog-post> {:id (:id post) :post post}])
       [loading/<query-fallback> (data/page-query index size) [<loading>]])
     (when (pos? total)
       [<blog-nav> {:total-posts total :current-idx index :posts-per-page size}])]))

(defc <blog-container>
  [{:keys [section tag-cloud?] :or {tag-cloud? true} :as spec}]
  [:section.blog.fullwide.noborder
   (if section
     section
     [loading/<spinner>])
   [:div.flex.center-content
    (when (or @(r/subscribe [:user/has-role? :bloggers]) @(r/subscribe [:user/has-role? :admins]))
      [:a.button.noborder {:href @(r/subscribe [:href :new-post])
                           :title "Post blog"
                           :aria-label "Post blog"}
       [:i.fa.fa-feather-alt]])

    [:a.button.blog-btn.noborder {:href @(r/subscribe [:href :blog])} "Home"]
    [:a.button.blog-btn.noborder {:href @(r/subscribe [:href :blog-archive])} "Archive"]]

    (when tag-cloud? [<blog-tag-cloud>])

   [:div.blog-powered-by.center-content
    [:p "Proudly powered by "
    [:a {:href "https://github.com/tolgraven/tolgraven"} "tolgrAVen"]
    [:i.fab.fa-github]]]])


(defpage <blog-page>
  {:depends (get content-contract/module-dependencies :blog)}
  []
  ;; One outer component identity preserves the heading across blog routes. Only
  ;; the selected content changes; SSR and SPA use the same route subscription.
  (let [route @(r/subscribe [:common/route])
        page (get-in route [:data :page])]
    [ui-layout/<with-heading> [:blog :heading]
     (case page
       :post [<blog-single-post>]
       :comment (when-enabled :blog :comments
                  [<blog-container> {:tag-cloud? false
                                     :section [comment-views/<standalone-comment> (:comment-id (page/selection route))]}])
       :tag [<blog-tag-view>]
       :archive [<blog-archive>]
       [<blog-container> {:section [<blog-feed>]}])]))
))
