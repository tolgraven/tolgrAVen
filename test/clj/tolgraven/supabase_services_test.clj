(ns tolgraven.supabase-services-test
  (:require [tolgraven.config :as config]
            [clojure.test :refer :all]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [tolgraven.platform.supabase :as platform]
            [tolgraven.supabase.auth :as auth]
            [tolgraven.supabase.api :as api]
            [tolgraven.supabase.storage :as storage]
            [tolgraven.supabase.integrations :as integrations])
  (:import [javax.imageio ImageIO]
           [java.awt.image BufferedImage]
           [java.io File]))

(deftest integration-settings-exclude-tokens
  (with-redefs [integrations/config! (constantly {:athlete_id 1 :intervals_athlete_id "i1"
                                                 :access_token "private" :client_secret "private"})]
    (is (= {:strava {:athlete_id 1 :intervals_athlete_id "i1"}} (integrations/settings!)))))

(deftest external-service-credentials-remain-on-the-server
  (let [calls (atom [])]
    (with-redefs [integrations/config! (fn [service]
                                        (case service
                                          "strava/auth" {:access_token "private-token" :expires_at 9999999999
                                                          :intervals_athlete_id "athlete" :intervals_api_key "private-intervals"}
                                          "instagram/auth" {:access_token "private-instagram"}
                                          "instagram/posts" {:post {:media_url "cached"}}))
                  http/request (fn [request]
                                 (swap! calls conj request)
                                 {:status 200 :body {:data []}})]
      (is (= {:data []} (integrations/strava! "athlete/activities")))
      (is (= "Bearer private-token" (get-in @calls [0 :headers "Authorization"])))
      (integrations/intervals! "athlete-summary{ext}?start=2026-01-01&end=2026-02-01")
      (is (= "https://intervals.icu/api/v1/athlete/athlete/athlete-summary?start=2026-01-01&end=2026-02-01" (:url (second @calls))))
      (is (= {:posts {}} (integrations/instagram!)))
      (is (= "private-instagram" (get-in @calls [2 :query-params :access_token])))
      (doseq [path ["../tokens" "https://evil.invalid" "activities/1%2fsecret" "athlete/../oauth/token"]]
        (is (= 400 (:status (integrations/response! #(integrations/strava! path)))))))))

(deftest expired-instagram-config-can-use-imported-media
  (with-redefs [integrations/config! #(if (= % "instagram/auth") {:access_token "expired"} {:post {:media_url "cached"}})
                http/request (fn [_] {:status 400 :body {:error "private-token"}})]
    (is (= {:posts {:post {:media_url "cached"}}} (integrations/instagram!)))))

(deftest refresh-failures-do-not-return-private-upstream-details
  (with-redefs [integrations/config! (constantly {:access_token "secret" :expires_at 0 :refresh_token "private"})
                http/request (fn [_] (throw (ex-info "Authorization: private" {:headers {"Authorization" "private"}})))]
    (let [response (integrations/response! #(integrations/strava! "athlete"))]
      (is (= 503 (:status response)))
      (is (not (.contains (pr-str response) "private"))))))

(deftest native-search-does-not-need-a-typesense-key
  (let [seen (atom nil)]
    (with-redefs [platform/request! (fn [method path opts] (reset! seen [method path opts]) {:body {:found 1 :hits []}})
                  integrations/config! (fn [_] (throw (Exception. "No legacy search config needed")))]
      (is (= {:found 1 :hits []} (integrations/search! "blog-posts" {"q" "cloj" "per_page" "10"})))
      (is (= "rpc/tolgraven_search" (second @seen)))
      (is (= {:p_collection "blog-posts" :p_query "cloj" :p_page 1 :p_per_page 10}
             (get-in @seen [2 :form-params])))
      (doseq [[collection params] [["service_configs" {"q" "secret"}]
                                  ["blog-posts" {"q" "x" "per_page" "99999"}]
                                  ["blog-comments" {"q" "x" "page" "0"}]]]
        (reset! seen nil)
        (is (= 400 (:status (integrations/response! #(integrations/search! collection params)))))
        (is (nil? @seen))))))

(deftest avatar-filenames-and-ownership-come-from-auth
  (let [file (File/createTempFile "tolgraven-avatar-test-" ".png")
        request (atom nil) profile (atom nil)]
    (try
      (ImageIO/write (BufferedImage. 2 2 BufferedImage/TYPE_INT_ARGB) "png" file)
      (with-redefs [platform/rest-base-url (constantly "https://example.invalid/")
                    platform/service-key (constantly "server-key")
                    auth/ensure-profile! (fn [user] (is (= "account" (:id user))) "verified-owner")
                    auth/save-profile! (fn [user data] (reset! profile [user data]) data)
                    http/post (fn [url opts] (reset! request [url opts]) {:status 200})]
        (storage/save-avatar! {:id "account"} {:tempfile file :size (.length file) :filename "victim.png"})
        (is (= "https://example.invalid/storage/v1/object/avatars/verified-owner.png" (first @request)))
        (is (= "true" (get-in @request [1 :headers "x-upsert"])))
        (is (= "Bearer server-key" (get-in @request [1 :headers "Authorization"])))
        (is (.startsWith (get-in @profile [1 :avatar]) "https://example.invalid/storage/v1/object/public/avatars/verified-owner.png?v="))
        (is (not (.contains (pr-str @profile) "server-key"))))
      (doseq [upload [{:tempfile file :size 5242881} {:size 0}]]
        (is (= 400 (:auth/status (ex-data (try (storage/png-bytes! upload) (catch Exception e e)))))))
      (spit file "Not an image")
      (is (= 400 (:auth/status (ex-data (try (storage/png-bytes! {:tempfile file :size (.length file)}) (catch Exception e e))))))
      (finally (.delete file)))))

(deftest server-configuration-supports-local-key-and-environment-precedence
  (with-redefs [config/env {:service-supabaseservice-key "local-secret"
                           :supabase-public-url "https://local.example"}]
    (with-redefs-fn {#'platform/env (constantly nil)}
      #(do (is (= "local-secret" (platform/service-key)))
           (is (= "https://local.example" (platform/rest-base-url)))))
    (with-redefs-fn {#'platform/env {"SUPABASE_SERVICE_KEY" "environment-secret"
                                    "SUPABASE_PUBLIC_URL" "https://environment.example"}}
      #(do (is (= "environment-secret" (platform/service-key)))
           (is (= "https://environment.example" (platform/rest-base-url)))))))


(deftest public-browser-bootstrap-survives-optional-service-failures
  (with-redefs [config/env {:supabase-public-url "https://public.example"
                           :supabase-anon-key "public-key"}
                platform/request! (fn [& _]
                                    (throw (ex-info "Missing Supabase service key" {})))
                http/get (fn [& _] (throw (ex-info "Auth unavailable" {})))]
    (is (= {:status 200
            :body {:url "https://public.example" :anon-key "public-key"
                   :trusted-author-ids [] :providers {}}}
           (api/settings-response)))))

(deftest public-settings-return-only-public-metadata
  (with-redefs [config/env {:supabase-public-url "https://public.example"
                           :supabase-anon-key "public-key"
                           :service-supabaseservice-key "private-service-key"}
                platform/request! (fn [& _] {:body [{:user_id "admin"}]})
                http/get (fn [& _] {:body {:external {:github true}}})]
    (is (= {:url "https://public.example" :anon-key "public-key"
            :trusted-author-ids ["admin"] :providers {:github true}}
           (api/public-settings)))
    (is (not (.contains (pr-str (api/settings-response)) "private-service-key")))))


(deftest missing-browser-settings-report-safe-actionable-configuration
  (with-redefs [config/env {}
                platform/request! (fn [& _] (throw (AssertionError. "Unexpected server request")))
                http/get (fn [& _] (throw (AssertionError. "Unexpected Auth request")))]
    (with-redefs-fn {#'api/environment-value (constantly nil)}
      #(is (= {:status 503
               :body {:error "Supabase browser configuration is missing"
                      :missing ["SUPABASE_PUBLIC_URL" "SUPABASE_ANON_KEY"]}}
              (api/settings-response))))))

(deftest rest-requests-have-bounded-overridable-timeouts
  (with-redefs [platform/service-key (constantly "private")
                platform/rest-url (constantly "https://database.test/rest/v1/table")
                http/request (fn [opts] {:status 200 :body opts})]
    (let [defaults (:body (platform/request! :get "table" {}))
          overridden (:body (platform/request! :get "table" {:socket-timeout 9000}))]
      (is (= 3000 (:conn-timeout defaults)))
      (is (= 15000 (:socket-timeout defaults)))
      (is (= 9000 (:socket-timeout overridden))))))

(deftest image-signing-requires-allowlisted-sources-and-protected-loader
  (let [settings {:host "https://images.test" :key "private"
                  :allowed-source-hosts ["media.test"] :loader-network-protected true}]
    (with-redefs [integrations/config! (constantly settings)]
      (is (= 302 (:status (integrations/image-response! "https://media.test/photo.jpg" "fit-in/640x480"))))
      (doseq [url ["http://media.test/a" "https://127.0.0.1/a" "https://169.254.169.254/a"
                   "https://media.test.evil/a" "https://media.test@internal/a"
                   "https://media.test:8443/a" "https://media.test/a#fragment" "invalid %"]]
        (is (= 400 (:status (integrations/response! #(integrations/image-response! url ""))))))
      (doseq [transforms ["filters:watermark(http://internal/a,0,0,0)" "99999x99999" "../"]]
        (is (= 400 (:status (integrations/response! #(integrations/image-response! "https://media.test/a" transforms)))))))
    (with-redefs [integrations/config! (constantly (dissoc settings :loader-network-protected))]
      (is (= 503 (:status (integrations/response! #(integrations/image-response! "https://media.test/a" ""))))))))
