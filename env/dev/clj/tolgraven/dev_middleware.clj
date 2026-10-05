(ns tolgraven.dev-middleware
  (:require
    [ring.middleware.reload :refer [wrap-reload]]
    [ring.middleware.lint :refer [wrap-lint]]
    [prone.middleware :refer [wrap-exceptions]]))

(defn wrap-dev [handler]
  (-> handler
      ;; Watch real source roots, not temporary aliases or optional prototypes.
      (wrap-reload {:dirs ["src/backend" "src/frontend" "src/cljc" "env/dev/clj"]})
      (wrap-exceptions {:app-namespaces ['tolgraven]})))
