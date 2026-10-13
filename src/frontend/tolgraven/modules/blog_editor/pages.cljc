(ns tolgraven.modules.blog-editor.pages
  "Authoring routes carry no editor implementation imports."
   #?(:cljs (:require [tolgraven.react :as r])))

(def spec
  [["/blog/new-post"
    {:name :new-post
     :page :page
     :module :blog-editor
     :transition-key :blog
     :depends [{:source :strapi, :availability :startup, :keys [:blog]}]
     :shell {:heading [:blog :heading], :loading-prefab :article}
     #?@(:cljs [:controllers [{:start (fn [_] (r/dispatch [:blog/init-posting]))
                              :stop (fn [_] (r/dispatch [:blog/cancel-edit]))}]])}]] )
