(ns website.modules.blog.pages
  "Page declarations independent of the module implementation."
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  (:require #?(:cljs [website.react :as r])
            #?(:clj [website.build.site :refer [when-enabled]])
            [clojure.string :as string]
            [website.navigation.component-routes :as component-routes]
            #?(:feature/blog-comments [website.modules.blog.comments :as comments])
            [website.modules.blog.data :as data]
            [website.page :as page]
            [website.schema.http :as schemas]))

#?(:clj (when-enabled :blog :comments
           (require '[website.modules.blog.comments :as comments])))

(def page-extension
  [:map [:?selection (into [:map [:?page [:int {:min 1}]]]
                          (when-enabled :blog :comments [[:?standalone-comment? :boolean]]))]])

#?(:cljs (def controllers
  {:blog [{:start (fn [_]
                    (r/dispatch [:blog/nav-page 1])
                    ;; Preserve the existing blog framing on enter/leave.
                    (r/dispatch [:->css-var! "line-width" "0.0625rem"])
                    (r/dispatch [:->css-var! "line-width-vert" "0.0625rem"]))
           :stop (fn []
                   (r/dispatch [:->css-var! "line-width" "0.125rem"])
                   (r/dispatch [:->css-var! "line-width-vert" "0.125rem"]))}]
   :blog-page [{:parameters {:path [:nr]}
                :start (fn [{:keys [path]}] (r/dispatch [:blog/nav-action (:nr path)]))}]
   :blog-post [{:parameters {:path [:permalink]}
                :start (fn [{:keys [path]}]
                         (let [id (-> path :permalink (string/split "-") last js/parseInt)]
                           (r/dispatch [:blog/state [:current-post-id] id])
                           #_(r/dispatch [:common/set-title "Blog post title: not implemented"])))
                :stop (fn [_]
                        (r/dispatch [:common/set-title nil]))}]
   :blog-tag [{:parameters {:path [:tag]}
               :start (fn [{:keys [path]}] (r/dispatch [:blog/state [:viewing-tag] (:tag path)]))
               :stop (fn [_] (r/dispatch [:blog/state [:viewing-tag] nil]))}]}))


(def spec
  (mapv (fn [[path data & children]] (into [path data] (remove nil? children)))
  ;; Native Reitit routes, with shared data inherited by each child page.
  [["/blog" {:schema page-extension :module :blog :transition-key :blog
              :shell {:heading [:blog :heading]
                      :loading-prefab :article
                      :loading-view {:module :blog :view :post-content}
                      :loading-args {:post {:title "A thought worth sharing"
                                            :user {:name "Author"}
                                            :text "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Integer vitae sapien sed lectus consequat feugiat.\n\nPraesent commodo cursus magna, vel scelerisque nisl consectetur et. Donec ullamcorper nulla non metus auctor fringilla.\n\nMaecenas sed diam eget risus varius blandit sit amet non magna. Aenean lacinia bibendum nulla sed consectetur."
                                            :tags ["thoughts" "updates"]}}
                      :loading-class "ssr-skeleton-article"}
              :depends [{:source :strapi :availability :startup :keys [:blog]}]
              :preload-depends (fn [match]
                                 (if (:ssr (:data match))
                                   [{:source :subscription
                                     :query [:blog/page-ready? (page/selection match)]}]
                                   (when-let [tag (get-in match [:path-params :tag])]
                                     [{:source :supabase :query (data/tag-query tag)}])))
              :data-plan data/plan :snapshot data/snapshot
              :document-title (fn [{:keys [posts]}]
                                (when (= 1 (count posts)) (:title (first posts))))}
    ["" {:name :blog :page :page :ssr true :selection {:page 1}
         #?@(:cljs [:controllers (:blog controllers)])}]
    ["/page/:nr" {:parameters {:path schemas/blog-page} :name :blog-page :page :page :ssr true
                  :ssr-parameters {:page {:from :nr :type :page-number}}
                  #?@(:cljs [:controllers (:blog-page controllers)])}]
    (component-routes/mount
      ["/post/:permalink" {:parameters {:path schemas/blog-post} :name :blog-post :page :post :ssr true
                        :ssr-parameters {:post-id {:from :permalink :type :trailing-id}}
                        #?@(:cljs [:controllers (:blog-post controllers)])}]
      (or (when-enabled :blog :comments
            [["/comment/:comment-id"
              {:name :blog-post-comment
               :document-target {:prefix "blog-comment-", :parameter :comment-id}
               :parameters {:path [:merge schemas/blog-post [:map [:comment-id comments/comment-id]]]}
               :ssr-parameters {:comment-id {:from :comment-id :type :document}}
               :component-route {:module :blog
                                 :view :comments
                                 :action (fn [{:keys [path]}] [:blog/navigate-comment (:comment-id path)])}
               #?@(:cljs [:controllers [{:parameters {:path [:permalink :comment-id]}
                                        :start (fn [_] (r/dispatch [:navigation/activate-component]))}]])}]])
          []))
    (when-enabled :blog :comments
      ["/comment/:comment-id"
       {:name :blog-comment
        :document-target "standalone-comment"
        :page :comment
        :ssr true
        :selection {:standalone-comment? true}
        :parameters {:path [:map [:comment-id comments/comment-id]]}
        :ssr-parameters {:comment-id {:from :comment-id :type :document}}
        #?@(:cljs [:controllers [{:parameters {:path [:comment-id]}
                                 :start (fn [{:keys [path]}]
                                          (r/dispatch [:blog/navigate-comment (:comment-id path)]))}]])}])
    ["/archive" {:name :blog-archive :page :archive}]
    ["/tag/:tag" {:parameters {:path schemas/blog-tag} :name :blog-tag :page :tag
                 #?@(:cljs [:controllers (:blog-tag controllers)])}]
]]))
