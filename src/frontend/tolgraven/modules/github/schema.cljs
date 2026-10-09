(ns tolgraven.modules.github.schema
  "Consumed provider fields and UI state; provider extension fields remain open."
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:pages-fetched [:sequential :int], :commits-fetched c/strings}))
(def options (c/optional-map {:user :string, :repo :string}))

(def author
  (c/optional-map {:name [:maybe :string]
                   :date [:maybe :string]
                   :email [:maybe :string]}))
(def commit
  (c/optional-map {:sha :string
                   :html_url :string
                   :url :string
                   :commit (c/optional-map {:message :string
                                            :author author
                                            :committer author})
                   :files [:sequential
                           (c/optional-map {:filename :string
                                            :status :string
                                            :patch :string
                                            :additions c/nonnegative
                                            :deletions c/nonnegative})]}))
(def content
  (c/optional-map {:commits [:sequential commit]
                   :repo [:sequential commit]
                   :commit [:map-of :string commit]
                   :repo-headers :map
                   :errors :any}))
