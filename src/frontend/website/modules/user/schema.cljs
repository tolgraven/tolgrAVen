(ns website.modules.user.schema
  (:require [website.schema.common :as c]
            [website.supabase.schema :as store]))

(def return-state-keys [:active-user :user :user-section])

(def options (c/optional-map {:auto-open? :boolean}))
(def login-fields (c/optional-map {:email :string, :password :string}))
(def password-fields (c/optional-map {:current :string, :new :string}))

(def user-id [:maybe c/id])

(def active-user [:maybe store/profile])

(def section [:sequential :keyword])
