(ns website.modules.supabase.module
  (:require [website.modules.supabase.client :as client]))

(def spec
  {:client client/api})
