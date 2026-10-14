(ns website.modules.neovim.http
  "Cross-origin isolation belongs only to the Neovim document and its workers."
  (:require [website.modules.neovim.policy :as policy]))

(defn wrap-isolation [handler]
  (fn [request]
    (let [response (handler request)
          headers (policy/headers-for (:uri request ""))]
      (if (and response headers)
        (update response :headers merge headers)
        response))))
