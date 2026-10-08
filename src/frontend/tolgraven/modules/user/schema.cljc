(ns tolgraven.modules.user.schema
  "Contracts owned by this feature, shared by browser and JVM validation."
  (:require [tolgraven.schema.common :as c]
            [tolgraven.supabase.schema :as store]))

(def options (c/optional-map {:auto-open? :boolean}))
(def login-fields (c/optional-map {:email :string, :password :string}))
(def password-fields (c/optional-map {:current :string, :new :string}))

(def user-id [:maybe c/id])

(def active-user [:maybe store/profile])

(def section [:sequential :keyword])
