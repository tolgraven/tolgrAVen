(ns website.modules.blog-editor.schema
  (:require [website.modules.markdown-editor.model :as model]
            [website.modules.blog-editor.draft-schema :as draft]
            [website.media.schema :as media]))

(def library-state
  [:map [:?pending? :boolean] [:?assets [:vector media/asset]]
   [:?next-offset [:maybe :int]] [:?error :string]])
(def sections {[:state :blog :image-uploads] [:maybe model/uploads-schema]
               [:state :blog-editor] [:maybe [:map
                                              [:?library library-state]
                                              [:?active-owner :string]
                                              [:?editors [:map-of :string model/editor-schema]]
                                              [:?draft draft/state]]]})
