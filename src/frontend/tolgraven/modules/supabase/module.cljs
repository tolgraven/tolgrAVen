(ns tolgraven.modules.supabase.module
  (:require [tolgraven.modules.supabase.client :as client]))

(def spec
  {:id :supabase
   :client client/api})
