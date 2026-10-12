(ns tolgraven.modules.instagram.api
  (:require [tolgraven.supabase.integrations :as integrations]))

(defn routes []
  (vec (remove nil?
   [["/integrations/instagram" {:get (fn [_] (integrations/response! integrations/instagram!))}]])))
