(ns tolgraven.blog.pages
  "Page declarations independent of the module implementation."
  (:require #?(:cljs [tolgraven.react :as rf])
            [clojure.string :as string]))

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
  [["/blog" {:module :blog}
    ["" {:name :blog :page :page :ssr true :data-source :blog :selection {:page 1}
         #?@(:cljs [:controllers (:blog controllers)])}]
    ["/page/:nr" {:name :blog-page :page :page :ssr true :data-source :blog
                  :ssr-parameters {:page {:from :nr :type :page-number}}
                  #?@(:cljs [:controllers (:blog-page controllers)])}]
    ["/post/:permalink" {:name :blog-post :page :post :ssr true :data-source :blog
                        :ssr-parameters {:post-id {:from :permalink :type :trailing-id}}
                        #?@(:cljs [:controllers (:blog-post controllers)])}]
    ["/archive" {:name :blog-archive :page :archive}]
    ["/tag/:tag" {:name :blog-tag :page :tag
                 #?@(:cljs [:controllers (:blog-tag controllers)])}]
    ["/new-post" {:name :new-post :page :new-post
                 #?@(:cljs [:controllers (:new-post controllers)])}]]])
