(ns website.modules.github.api
  (:require [website.modules.github.activity :as activity]
            [website.modules.github.provider :as provider]
            [website.modules.github.archive :as archive]
            [website.modules.github.archive-schema :as archive-schema]
            [website.modules.github.detail :as detail]
            [website.modules.github.detail-schema :as detail-schema]
            [website.modules.github.settings :as settings]
            [website.modules.github.configuration-schema :as configuration-schema]
            [website.components.activity-schema :as schema]))

(defn routes []
  [["/github/settings"
    {:get {:responses {200 {:body configuration-schema/result}}
           :handler (fn [_] (settings/response))}}]
   ["/github/activity"
    {:get {:parameters {:query [:map [:user activity/user-schema]]}
           :responses {200 {:body schema/result}}
           :handler (fn [{{{:keys [user]} :query} :parameters}]
                      (activity/response! user))}}]
   ["/github/activity/day"
    {:get {:parameters {:query [:map [:user activity/user-schema]
                                [:date [:and :string [:fn activity/valid-date?]]]]}
           :responses {200 {:body schema/breakdown}}
           :handler (fn [{{{:keys [user date]} :query} :parameters}]
                      (activity/day-response! user date))}}]
   ["/github/commits"
    {:get {:responses {200 {:body archive-schema/result}}
           :parameters {:query [:map
                                [:user [:and :string [:fn provider/user?]]]
                                [:repo [:and :string [:fn provider/repo?]]]
                                [:q {:optional true} [:string {:max 300}]]
                                [:cursor {:optional true} [:and :string [:fn archive/cursor?]]]]}
           :handler (fn [{{query :query} :parameters}] (archive/response! query))}}]
   ["/github/commits/:sha"
    {:get {:parameters {:path [:map [:sha detail-schema/reference]]
                        :query [:map
                                [:user [:and :string [:fn provider/user?]]]
                                [:repo [:and :string [:fn provider/repo?]]]
                                [:page {:optional true} [:int {:min 1, :max 30}]]]}
           :responses {200 {:body detail-schema/result}}
           :handler (fn [{{path :path, query :query} :parameters}]
                      (detail/response! (merge (select-keys query [:user :repo :page]) path)))}}]
   ["/github/commits/:sha/patch"
    {:get {:parameters {:path [:map [:sha detail-schema/reference]]
                        :query [:map
                                [:user [:and :string [:fn provider/user?]]]
                                [:repo [:and :string [:fn provider/repo?]]]]}
           :responses {200 {:body [:string {:max 1048576}]}}
           :handler (fn [{{path :path, query :query} :parameters}]
                      (detail/response! (assoc (merge (select-keys query [:user :repo]) path) :kind :patch)))}}]
   ["/github/contents"
    {:get {:parameters {:query [:map
                                [:user [:and :string [:fn provider/user?]]]
                                [:repo [:and :string [:fn provider/repo?]]]
                                [:ref archive-schema/sha]
                                [:path detail-schema/file-path]]}
           :responses {200 {:body detail-schema/source}}
           :handler (fn [{{query :query} :parameters}]
                      (detail/response! (assoc (select-keys query [:user :repo :path])
                                              :sha (:ref query) :kind :source)))}}]])
