(ns website.modules.blog-editor.module
  (:require [website.modules.blog-editor.pages :as pages]
            [website.modules.blog-editor.schema :as schema]
            [website.modules.blog-editor.events]
            [website.modules.blog-editor.views :as views]))

(def spec
  {:bundle/depends-on #{:main :blog :highlight}
   :site/owner :blog
   :pages pages/spec
   :db-schema schema/sections
   :view {:page #'views/<page>}})
