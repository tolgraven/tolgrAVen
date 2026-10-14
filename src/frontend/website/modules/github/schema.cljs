(ns website.modules.github.schema
  "Consumed provider fields and UI state; provider extension fields remain open."
  (:require [website.schema.common :as c]))

(def state
  (c/optional-map {:target [:tuple :string :string]
                   :generation c/nonnegative
                   :pages-pending [:set :int]
                   :commits-pending [:set :string]
                   :headers-pending? :boolean
                   :query :string
                   :pages-fetched [:sequential :int]
                   :commits-fetched c/strings}))
(def options (c/optional-map {:user :string, :repo :string}))

(def author
  (c/optional-map {:name :?string
                   :date :?string
                   :email :?string}))
(def parsed-message [:tuple :string :?string :?string])

(def commit
  (c/optional-map {:sha :string
                   :sha7 :?string
                   :date :?string
                   :clock :?string
                   :ts :?string
                   :message parsed-message
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
                   :commit-errors [:map-of :string :any]
                   :repo-headers :map
                   :total c/nonnegative
                   :next-cursor :?string
                   :complete? :boolean
                   :errors :any}))
