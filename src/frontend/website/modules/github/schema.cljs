(ns website.modules.github.schema
  "Consumed provider fields and UI state; provider extension fields remain open."
  (:require [website.schema.common :as c]))

(def return-state-keys
  ;; These identify the cached feed and its completed pages. Pending requests
  ;; belong to the old document and must be acquired again after restoration.
  [:target :query :generation :pages-fetched :commits-fetched])
(def return-form-field-keys [:github])

(def result
  [:map
   [:status [:enum :loading :ready :error]]
   [:?attempt c/nonnegative]
   [:?message :string]])
(def diff-state
  [:map
   [:mode [:enum :diff :patch]]
   [:selected :string]
   [:metadata result]
   [:?displayed [:map [:mode [:enum :diff :patch]] [:selected :string]]]
   [:?patch result]
   [:?files [:map-of :string result]]])
(def state
  (c/optional-map {:target [:tuple :string :string]
                   :generation c/nonnegative
                   :pages-pending [:set :int]
                   :commits-pending [:set :string]
                   :headers-pending? :boolean
                   :query :string
                   :pages-fetched [:sequential :int]
                   :commits-fetched c/strings
                   :diff-attempt c/nonnegative
                   :diff-view [:map-of :string diff-state]}))
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
                   :parents [:vector (c/optional-map {:sha :string})]
                   :files [:sequential
                           (c/optional-map {:filename :string
                                            :status :string
                                            :previous_filename :string
                                            :patch :string
                                            :additions c/nonnegative
                                            :deletions c/nonnegative})]}))
(def content
  (c/optional-map {:commits [:sequential commit]
                   :repo [:sequential commit]
                   :commit [:map-of :string commit]
                   :commit-errors [:map-of :string :any]
                   :repo-headers :map
                   :diff-view [:map-of :string
                               (c/optional-map {:commit commit
                                                :patch [:string {:max 1048576}]
                                                :files [:map-of :string
                                                        (c/optional-map {:before [:string {:max 1048576}]
                                                                         :after [:string {:max 1048576}]})]})]
                   :total c/nonnegative
                   :next-cursor :?string
                   :complete? :boolean
                   :errors :any}))
