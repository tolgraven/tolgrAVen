(ns tolgraven.dev-middleware
  (:require
    [ring.middleware.reload :refer [wrap-reload]]
    [ring.middleware.lint :refer [wrap-lint]]
    [prone.middleware :refer [wrap-exceptions]]))

(defn wrap-dev [handler]
  (-> handler
      wrap-reload
      (wrap-exceptions {:app-namespaces ['tolgraven]})
      wrap-lint))
