(ns tolgraven.modules.github.api
  (:require [tolgraven.modules.github.activity :as activity]
            [tolgraven.components.activity-schema :as schema]))

(defn routes []
  [["/github/activity"
    {:get {:parameters {:query [:map [:user activity/user-schema]]}
           :responses {200 {:body schema/result}}
           :handler (fn [{{{:keys [user]} :query} :parameters}]
                      (activity/response! user))}}]])
