(ns tolgraven.blog.routes
  (:require [re-frame.core :as rf]
            [clojure.string :as string]))

;; Route metadata stays in the main bundle; views are resolved by the module loader.
(def routes
  ["blog"
      ["" {:name        :blog
           :module      :blog
           :page        :page
           :controllers [{:start (fn [_]
                                   (rf/dispatch [:blog/nav-page 1]) ; down here so back-btn works from page/2 to blog aka page/1.
                                   (rf/dispatch [:->css-var! "line-width" "1px"]) ; TODO fix so does this without hardcoding either. Might also set line-color to something less pronounced.
                                   (rf/dispatch [:->css-var! "line-width-vert" "1px"]))
                          :stop  (fn []
                                   (rf/dispatch [:->css-var! "line-width" "2px"])
                                   (rf/dispatch [:->css-var! "line-width-vert" "2px"]))}]}] ; needed here so going back from blog/page/2 to blog returns one to page 1...
      ["/page/:nr"
       {:name   :blog-page
        :module :blog
        :page   :page
        :controllers
        [{:parameters {:path [:nr]}
          :start      (fn [{:keys [path]}]
                        (rf/dispatch [:blog/nav-action (:nr path)]))
          :stop       (fn [{:keys [path]}])}]}]
      ["/post/:permalink"
       {:name   :blog-post
        :module :blog
        :page   :post
        :controllers
        [{:parameters {:path [:permalink]}
          :start      (fn [{:keys [path] :as data}]
                        (let [id (-> path :permalink (string/split "-") last js/parseInt)]
                          (rf/dispatch [:blog/state [:current-post-id] id])
                          #_(rf/dispatch [:common/set-title "Blog post title: not implemented"])))
          :stop       (fn [{:keys [path] :as data}]
                        (rf/dispatch [:blog/state [:current-post-id] nil])
                        (rf/dispatch [:common/set-title nil]))}]}]
      ["/archive" {:name   :blog-archive
                   :module :blog
                   :page   :archive}]
      ["/tag/:tag" {:name   :blog-tag
                    :module :blog
                    :page   :tag
                    :controllers
                    [{:parameters {:path [:tag]}
                      :start      (fn [{:keys [path]}]
                                    (rf/dispatch [:blog/state [:viewing-tag] (:tag path)]))
                      :stop       (fn [{:keys [path]}]
                                    (rf/dispatch [:blog/state [:viewing-tag] nil]))}]}]
      ["/new-post" {:name        :new-post
                   :module      :blog
                   :page        :new-post
                   :controllers [{:start (fn [_] (rf/dispatch [:blog/init-posting]))}
                                 {:stop (fn [_] (rf/dispatch [:blog/cancel-edit]))}]}]])
