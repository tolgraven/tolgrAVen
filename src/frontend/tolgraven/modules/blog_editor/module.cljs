(ns tolgraven.modules.blog-editor.module
  {:bundle/depends-on #{:main :blog}
   :site/owner :blog}
  (:require [tolgraven.modules.blog-editor.pages :as pages]
            [tolgraven.modules.blog-editor.schema :as schema]
            [tolgraven.modules.blog-editor.events]
            [tolgraven.modules.blog-editor.views :as views]))

(def spec
  {:id :blog-editor
   :pages pages/spec
   :styles ["/css/tolgraven/modules/blog-editor.min.css"]
   :db-schema schema/sections
   :view {:page #'views/<page>}})
