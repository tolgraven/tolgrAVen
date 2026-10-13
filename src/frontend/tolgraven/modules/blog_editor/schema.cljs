(ns tolgraven.modules.blog-editor.schema
  (:require [tolgraven.modules.blog-editor.model :as model]
            [tolgraven.modules.blog-editor.draft-schema :as draft]
            [tolgraven.media.schema :as media]))

(def library-state
  [:map [:?pending? :boolean] [:?assets [:vector media/asset]]
   [:?next-offset [:maybe :int]] [:?error :string]])
(def sections {[:state :blog :image-uploads] [:maybe model/uploads-schema]
               [:state :blog-editor] [:maybe [:map [:?library library-state] [:?active-owner :string] [:?draft draft/state]]]})
