(ns tolgraven.blog.pages
  "Page declarations independent of the module implementation."
  (:require #?(:cljs [tolgraven.react :as rf])
            [clojure.string :as string]
            [tolgraven.blog.data :as data]
            [tolgraven.page :as page]))

#?(:cljs (def controllers
  {:blog [{:start (fn [_]
                    (rf/dispatch [:blog/nav-page 1])
                    ;; Preserve the existing blog framing on enter/leave.
                    (rf/dispatch [:->css-var! "line-width" "1px"])
                    (rf/dispatch [:->css-var! "line-width-vert" "1px"]))
           :stop (fn []
                   (rf/dispatch [:->css-var! "line-width" "2px"])
                   (rf/dispatch [:->css-var! "line-width-vert" "2px"]))}]
   :blog-page [{:parameters {:path [:nr]}
                :start (fn [{:keys [path]}] (rf/dispatch [:blog/nav-action (:nr path)]))}]
   :blog-post [{:parameters {:path [:permalink]}
                :start (fn [{:keys [path]}]
                         (let [id (-> path :permalink (string/split "-") last js/parseInt)]
                           (rf/dispatch [:blog/state [:current-post-id] id])
                           #_(rf/dispatch [:common/set-title "Blog post title: not implemented"])))
                :stop (fn [_]
                        (rf/dispatch [:common/set-title nil]))}]
   :blog-tag [{:parameters {:path [:tag]}
               :start (fn [{:keys [path]}] (rf/dispatch [:blog/state [:viewing-tag] (:tag path)]))
               :stop (fn [_] (rf/dispatch [:blog/state [:viewing-tag] nil]))}]
   :new-post [{:start (fn [_] (rf/dispatch [:blog/init-posting]))}
              {:stop (fn [_] (rf/dispatch [:blog/cancel-edit]))}]}))

(def spec
  ;; Native Reitit routes, with shared data inherited by each child page.
  [["/blog" {:module :blog :transition-key :blog
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
    ["/page/:nr" {:name :blog-page :page :page :ssr true
                  :ssr-parameters {:page {:from :nr :type :page-number}}
                  #?@(:cljs [:controllers (:blog-page controllers)])}]
    ["/post/:permalink" {:name :blog-post :page :post :ssr true
                        :ssr-parameters {:post-id {:from :permalink :type :trailing-id}}
                        #?@(:cljs [:controllers (:blog-post controllers)])}]
    ["/archive" {:name :blog-archive :page :archive}]
    ["/tag/:tag" {:name :blog-tag :page :tag
                 #?@(:cljs [:controllers (:blog-tag controllers)])}]
    ["/new-post" {:name :new-post :page :new-post
                 #?@(:cljs [:controllers (:new-post controllers)])}]]])
