(ns website.modules.supabase.module
  (:require [website.modules.supabase.client :as client]))

(def spec
  {:id :supabase
   :client client/api})
