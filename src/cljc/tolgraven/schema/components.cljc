(ns tolgraven.schema.components
  "Input contracts shared by reusable UI primitives and their callers/tests."
  (:require [malli.util :as mu]
            [tolgraven.content.schema :as content]))

(def image-attrs
  ;; Pending avatars intentionally reserve their box without an image request.
  ;; CMS media uses a concrete source; a mounted image may temporarily omit it.
  (mu/merge content/media [:map [:src {:optional true} [:maybe :string]]
                               [:on-error {:optional true} [:maybe fn?]]
                               [:on-load {:optional true} [:maybe fn?]]
                               [:style {:optional true} [:maybe :map]]]))
