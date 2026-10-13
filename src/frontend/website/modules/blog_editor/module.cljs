(ns website.modules.blog-editor.module
  {:bundle/depends-on #{:main :blog}
   :site/owner :blog}
  (:require [website.modules.blog-editor.pages :as pages]
            [website.modules.blog-editor.schema :as schema]
            [website.modules.blog-editor.events]
            [website.modules.blog-editor.views :as views]))

(def spec
  {:id :blog-editor
   :pages pages/spec
   :styles ["/css/tolgraven/modules/blog-editor.min.css"]
   :db-schema schema/sections
   :view {:page #'views/<page>}})
