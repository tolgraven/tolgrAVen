(ns website.modules.instagram.api
  (:require [website.supabase.integrations :as integrations]))

(defn routes []
  (vec (remove nil?
   [["/integrations/instagram" {:get (fn [_] (integrations/response! integrations/instagram!))}]])))
