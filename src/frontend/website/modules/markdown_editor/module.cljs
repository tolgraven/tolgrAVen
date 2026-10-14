(ns website.modules.markdown-editor.module
  (:require [website.modules.markdown-editor.entry :as entry]
            [website.modules.markdown-editor.schema :as schema]))

;; Reading and editing share the Markdown parser. Give it one lazy ancestor
;; instead of letting Shadow hoist those packages into their common main.
(def spec
  {:bundle/depends-on #{:main :markdown :markdown-components :highlight}
   :site/owner :blog
   :styles ["/css/website/modules/markdown-editor.min.css"]
   :db-schema schema/sections
   :view {:editor #'entry/<editor>
          :composer #'entry/<composer>}})
