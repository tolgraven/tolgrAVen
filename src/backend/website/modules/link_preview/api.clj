(ns website.modules.link-preview.api
  (:require [website.modules.link-preview.service :as link-preview]
            [website.modules.link-preview.article :as preview-contract]))

(defn routes []
  (vec (remove nil?
   [["/link-preview"
    {:get {:summary "Readable public article preview"
           :parameters {:query preview-contract/query}
           :responses {200 {:body preview-contract/result}}
           :handler (fn [request]
                      (link-preview/response! (get-in request [:parameters :query :url])))}}]])))
