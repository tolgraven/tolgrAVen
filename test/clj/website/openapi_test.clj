(ns website.openapi-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [reitit.ring :as ring]
            [ring.mock.request :as mock]
            [website.config :as config]
            [website.routes.services :as services]))

(deftest api-description-compatibility-test
  (with-redefs [config/env {:validation {:enabled false}}]
    (let [handler (ring/ring-handler (ring/router [(services/service-routes)]))
          get-json (fn [path]
                     (let [response (handler (-> (mock/request :get path)
                                                  (mock/header "accept" "application/json")))]
                       (is (= 200 (:status response)))
                       (json/read-str (if (string? (:body response)) (:body response) (slurp (:body response))))))
          legacy (get-json "/api/swagger.json")
          modern (get-json "/api/openapi.json")
          post-schema (get-in modern ["paths" "/api/math/plus" "post" "requestBody" "content" "application/json" "schema"])]
      (let [ui-config (get-json "/api/api-docs/config.json")]
        (is (= "/api/openapi.json" (get ui-config "url"))))
      (is (= "2.0" (get legacy "swagger")))
      (is (= "3.1.0" (get modern "openapi")))
      (is (= (set (keys (get legacy "paths"))) (set (keys (get modern "paths")))))
      (is (= "object" (get post-schema "type")))
      (is (= #{"x" "y"} (set (get post-schema "required"))))
      (is (= ["math"] (get-in modern ["paths" "/api/math/plus" "get" "tags"])))
      (doseq [path ["/api/supabase/avatar" "/api/supabase/media" "/api/supabase/comment-media"]
              :when (contains? (get modern "paths") path)]
        (is (= {"type" "string" "format" "binary"}
               (get-in modern ["paths" path "post" "requestBody" "content" "multipart/form-data" "schema" "properties" "file"])))
        (is (= "file" (get (first (filter #(= "file" (get % "name"))
                                                        (get-in legacy ["paths" path "post" "parameters"]))) "type"))))
      (is (= "binary" (get-in modern ["paths" "/api/files/download" "get" "responses" "200" "content" "image/png" "schema" "format"])))
      (is (not (contains? (get modern "paths") "/api/openapi.json")))
      (let [response (handler (-> (mock/request :get "/api/math/plus?x=2&y=3")
                                  (mock/header "accept" "application/json")))]
        (is (= 200 (:status response)))
        (is (= {"total" 5} (json/read-str (if (string? (:body response)) (:body response) (slurp (:body response))))))))))
