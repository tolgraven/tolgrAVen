(ns website.modules.markdown-editor.schema
  (:require [website.modules.markdown-editor.model :as model]))

(def sections
  {[:state :blog :image-uploads] [:maybe model/uploads-schema]
   [:state :blog-editor :editors] [:maybe [:map-of :string model/editor-schema]]
   [:state :blog-editor :active-owner] :?string})
