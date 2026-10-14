(ns website.modules.github.api
  (:require [website.modules.github.activity :as activity]
            [website.components.activity-schema :as schema]))

(defn routes []
  [["/github/activity"
    {:get {:parameters {:query [:map [:user activity/user-schema]]}
           :responses {200 {:body schema/result}}
           :handler (fn [{{{:keys [user]} :query} :parameters}]
                      (activity/response! user))}}]])
