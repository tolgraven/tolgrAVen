(ns website.media-pipeline-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [website.config :as config]
            [website.content.service :as content]
            [website.content.schema :as content-schema]
            [website.media.cms :as cms]
            [website.media.image :as image]
            [website.media.pipeline :as pipeline]
            [website.media.query :as query]
            [website.media.schema :as schema]
            [website.media.storage :as storage]
            [website.platform.supabase :as platform]
            [website.schema.dsl :as dsl]
            [website.site :as site]
            [website.supabase.auth :as auth]
            [website.modules.main.api :as main-api]
            [reitit.core :as reitit]
            [reitit.ring :as ring]
            [website.store.contract :as contract]
            [website.supabase.operations :as operations]
            [website.supabase.query :as store-query]
            [malli.core :as m])
  (:import [java.io ByteArrayInputStream]
           [java.nio.file Files]
           [java.io ByteArrayOutputStream]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
           [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(def rendition {:src "https://storage.test/original.png" :width 100 :height 80 :bytes 25 :mime "image/png"})
(def asset {:id "asset" :src (:src rendition) :width 100 :height 80
            :placeholder {:colors ["#ff0000" "#008000" "#0000ff"]}
            :renditions {:original rendition
                         :webp [(assoc rendition :src "https://storage.test/a.webp" :mime "image/webp")]
                         :avif [(assoc rendition :src "https://storage.test/a.avif" :mime "image/avif")]}})
(def source-bytes (byte-array (map unchecked-byte [137 80 78 71 13 10 26 10 0])))

(deftest public-media-contracts-and-declarative-queries
  (is (m/validate (dsl/->malli schema/asset) asset))
  (is (m/validate (dsl/->malli schema/upload-response) {:id "a" :status "queued"}))
  (is (not (m/validate (dsl/->malli schema/upload-response) {:id "a" :status "ready"})))
  (is (not (m/validate (dsl/->malli schema/upload-response) {:id "a" :status "queued" :asset asset})))
  (is (m/validate (dsl/->malli schema/placeholder)
                  {:colors ["#ff0000" "#008000" "#0000ff"]}))
  (is (not (m/validate (dsl/->malli schema/placeholder) {:colors ["url(https://untrusted.test)" "#ff0000" "#ff0000"]})))
  (let [queries (query/assets [(:src asset) (:src asset)])]
    (is (= 2 (count queries)))
    (doseq [opts queries]
      (is (store-query/public-read-query? opts))
      (is (= "site_media" (:table (first (store-query/seed-load-plan opts)))))
      (is (= "id,source_url,canonical_src,status,asset,updated_at"
             (:select (first (store-query/seed-load-plan opts)))))))
  (is (= ["https://storage.test/a.png" "https://storage.test/b.png"]
         (query/markdown-sources "![a](https://storage.test/a.png) ![b](<https://storage.test/b.png> \"title\")"))))

(deftest canonical-component-images-use-the-shared-literal-source-contract
  (let [url "https://storage.test/components/photo.avif"
        direct (str "<Component component=\"website.components.content.markdown/<image>\" src=\"" url "\" alt=\"Photo\" layout-width=\"20rem\" />")
        positional (str "<Component component=\"website.components.image/<picture>\" args='json:[{\"src\":\"" url "\",\"alt\":\"Photo\"}]' />")]
    (is (= [url] (query/markdown-sources (str direct "\n" positional))))
    (is (= [url] (query/markdown-sources
                   (str "<Component component='website.components.image/<picture>' args='edn:[{:src &quot;" url "&quot;}]' />"))))
    (is (= [url] (query/markdown-sources
                   (str "<Component component='website.components.image/<picture>' src='json:&quot;" url "&quot;' />"))))
    (doseq [source [(str "<Component component='anything' src={\"" url "\"} />")
                    (str "<Component component='anything' {...data} src='" url "' />")
                    (str "<Component component='anything' src='" url "' src='different' />")
                    "<Component component='anything' src='db:[:content :common :image]' />"
                    "<Component component='anything' args='json:invalid' />"]]
      (is (empty? (query/markdown-sources source))))))

(deftest rich-post-save-reacquires-ready-manifests-instead-of-authored-metadata
  (let [calls (atom [])
        source (str "<Component component='website.components.content.markdown/<image>' src='" (:src asset) "' alt='Photo' />")]
    (with-redefs [pipeline/assets! (fn [sources] (is (= [(:src asset)] sources)) {(:src asset) asset})
                  platform/request! (fn [_ _ opts] (swap! calls conj opts) {:body {}})
                  website.supabase.auth/ensure-profile! (constantly "author")]
      (operations/save-post! {:id "author"} {:title "Rich image" :text source :tags "tag"})
      (is (= {(:src asset) asset} (get-in (first @calls) [:form-params :p_image_assets]))))))

(deftest originals-are-bounded-and-sniffed-before-queueing
  (is (= "png" (storage/extension! source-bytes)))
  (is (= "svg" (storage/extension! (.getBytes "<svg>" "UTF-8"))))
  (is (thrown? clojure.lang.ExceptionInfo (storage/extension! (.getBytes "<html>" "UTF-8"))))
  (is (thrown? clojure.lang.ExceptionInfo
               (storage/limited-bytes! (ByteArrayInputStream. (byte-array 20)) 10)))
  (let [calls (atom [])]
    (with-redefs [pipeline/enabled? (constantly true)
                  storage/put! (fn [& args] (swap! calls conj [:original (first args)]))
                  platform/request! (fn [_ path opts]
                                      (swap! calls conj [:queue path])
                                      {:body [(assoc (get-in opts [:form-params :p_job]) :status "queued")]})]
      (let [result (pipeline/queue-bytes! source-bytes {:owner-id "author" :source-key "upload:author"
                                                      :source-type "upload"})]
        (is (= "queued" (:status result)))
        (is (= [:original :queue] (mapv first @calls)))
        (is (not (contains? result :asset)))))))

(deftest authorship-and-status-do-not-trust-user-metadata
  (let [user {:id "author" :user_metadata {:roles ["admins"]}}]
    (with-redefs [platform/request! (fn [& _] {:body []})]
      (is (thrown? clojure.lang.ExceptionInfo (pipeline/author! user))))
    (with-redefs [pipeline/roles! (constantly ["bloggers"])]
      (is (= "author" (pipeline/author! user))))
    (with-redefs [pipeline/job! (constantly {:id "job" :status "queued" :owner_id "another"})
                  pipeline/roles! (constantly [])]
      (is (= 404 (:auth/status (ex-data (try (pipeline/status! user "job")
                                           (catch clojure.lang.ExceptionInfo error error)))))))))

(deftest publication-is-all-or-nothing-and-completion-is-lease-guarded
  (let [calls (atom [])
        job {:id "job" :content_hash (storage/digest source-bytes) :source_object "private/source"
             :source_ext "png" :policy_version "v1" :lease_owner "lease" :attempts 1}]
    (with-redefs [storage/original! (constantly source-bytes)
                  image/with-renditions! (fn [_ _ publish!] (publish! nil nil))
                  pipeline/publish! (fn [& _] (swap! calls conj :formats) asset)
                  platform/request! (fn [_ _ opts] (swap! calls conj opts))]
      (pipeline/process! job)
      (is (= :formats (first @calls)))
      (is (= "eq.lease" (get-in (second @calls) [:query-params "lease_owner"])))
      (is (= "eq.processing" (get-in (second @calls) [:query-params "status"])))
      (is (= "ready" (get-in (second @calls) [:form-params :status]))))
    (reset! calls [])
    (with-redefs [storage/original! (constantly source-bytes)
                  image/with-renditions! (fn [& _] (throw (ex-info "codec failure" {})))
                  platform/request! (fn [_ _ opts] (swap! calls conj opts))]
      (pipeline/process! job)
      (is (= "queued" (get-in (first @calls) [:form-params :status])))
      (is (not (contains? (:form-params (first @calls)) :asset))))))

(deftest ready-projection-excludes-private-jobs-and-content-enrichment-is-owned
  (let [rows [{:id "ready" :source_url "https://cms.test/uploads/a.png" :status "ready" :asset asset}
              {:id "queued" :status "queued" :owner_id "private" :source_object "private/source"}]
        result (get (contract/seed->contract {:site_media rows}) "media-assets")]
    (is (= #{"ready"} (set (keys result))))
    (is (not (contains? (get result "ready") :owner_id))))
  (with-redefs [pipeline/assets! (constantly {"https://cms.test/uploads/a.png" asset})]
    (let [enriched (content/enrich-media! {:cv {:logo "https://cms.test/uploads/a.png"
                                              :heading {:bg {:src "https://cms.test/uploads/a.png" :alt "Author alt"}}}})]
      (is (= "https://cms.test/uploads/a.png" (get-in enriched [:cv :logo])))
      (is (= asset (get-in enriched [:cv :image-assets "https://cms.test/uploads/a.png"])))
      (is (= (:src asset) (get-in enriched [:cv :heading :bg :src])))
      (is (= "Author alt" (get-in enriched [:cv :heading :bg :alt])))
      (is (= (:placeholder asset) (get-in enriched [:cv :heading :bg :placeholder])))
      (is (m/validate (dsl/->malli content-schema/media) (get-in enriched [:cv :heading :bg]))))))

(deftest post-save-derives-manifests-and-rejects-client-forgery
  (let [calls (atom [])]
    (with-redefs [pipeline/assets! (fn [sources] (is (= [(:src asset)] sources)) {(:src asset) asset})
                  platform/request! (fn [_ _ opts] (swap! calls conj opts) {:body {}})
                  website.supabase.auth/ensure-profile! (constantly "author")]
      (operations/save-post! {:id "author"} {:title "Title" :text (str "![a](" (:src asset) ")") :tags "tag"})
      (is (= {(:src asset) asset} (get-in (first @calls) [:form-params :p_image_assets])))
      (is (thrown? clojure.lang.ExceptionInfo
                   (operations/save-post! {:id "author"} {:title "Title" :text "Body" :image-assets {"fake" asset}}))))))

(deftest cms-webhooks-are-authenticated-and-cannot-select-another-origin
  (with-redefs [pipeline/enabled? (constantly true)
                config/env {:media {:strapi-webhook-token "private-webhook-token"}}
                content/settings (constantly {:url "https://cms.test"})
                storage/public-url #(str "https://storage.test/" %)]
    (is (= "https://cms.test/uploads/a.png" (str (cms/source-uri! "/uploads/a.png"))))
    (doseq [url ["https://other.test/uploads/a.png" "/uploads/../secret" "/uploads/a.png?target=secret"]]
      (is (thrown? clojure.lang.ExceptionInfo (cms/source-uri! url))))
    (is (= 401 (:auth/status (ex-data (try (cms/webhook! {:headers {"authorization" "Bearer wrong"}
                                                       :body-params {:event "media.create" :media {:id 1 :url "/uploads/a.png"}}})
                                         (catch clojure.lang.ExceptionInfo error error))))))
    (with-redefs [cms/intake! (fn [media] {:accepted (:id media)})]
      (is (= {:accepted 1} (cms/webhook! {:headers {"authorization" "Bearer private-webhook-token"}
                                         :body-params {:event "media.create" :media {:id 1 :url "/uploads/a.png"}}}))))))

(deftest real-storage-http-boundary-reuses-immutable-objects-and-closes-downloads
  (let [objects (atom {})
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/storage/v1/object"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [path (.getPath (.getRequestURI exchange))
                  method (.getRequestMethod exchange)
                  authorization (.getFirst (.getRequestHeaders exchange) "Authorization")
                  [status bytes mime]
                  (cond
                    (not= "Bearer fixture" authorization) [401 (.getBytes "{}" "UTF-8") "application/json"]
                    (= "POST" method)
                    (if (contains? @objects path)
                      [400 (.getBytes "{\"error\":\"Duplicate\"}" "UTF-8") "application/json"]
                      (let [bytes (.readAllBytes (.getRequestBody exchange))]
                        (swap! objects assoc path bytes)
                        [200 (.getBytes "{}" "UTF-8") "application/json"]))
                    :else [200 (get @objects (clojure.string/replace path "/authenticated/" "/")) "image/png"])]
              (.add (.getResponseHeaders exchange) "Content-Type" mime)
              (.sendResponseHeaders exchange status (alength ^bytes bytes))
              (with-open [output (.getResponseBody exchange)] (.write output ^bytes bytes)))
            (finally (.close exchange))))))
    (.start server)
    (try
      (let [base (str "http://127.0.0.1:" (.getPort (.getAddress server)))]
        (with-redefs [platform/rest-base-url (constantly base)
                      platform/service-key (constantly "fixture")]
          (storage/put! "media-originals/a.png" "image/png" source-bytes)
          (storage/put! "media-originals/a.png" "image/png" source-bytes)
          (is (= (seq source-bytes) (seq (storage/original! "media-originals/a.png"))))
          (is (= 1 (count @objects)))))
      (finally (.stop server 0)))))

(deftest authorized-library-pages-reuse-ready-assets-and-bound-access
  (let [calls (atom [])]
    (with-redefs [pipeline/enabled? (constantly true)
                  pipeline/roles! (constantly ["bloggers"])
                  platform/request! (fn [_ _ opts] (swap! calls conj opts) {:body [{:asset asset}]})]
      (is (= {:assets [asset] :next-offset 1} (pipeline/library! {:id "author"} {:limit 1})))
      (is (= "(owner_id.eq.author,source_type.eq.strapi)" (get-in (first @calls) [:query-params "or"])))
      (is (= "eq.ready" (get-in (first @calls) [:query-params "status"])))
      (is (thrown? clojure.lang.ExceptionInfo (pipeline/library! {:id "author"} {:limit 51}))))
    (reset! calls [])
    (with-redefs [pipeline/enabled? (constantly true)
                  pipeline/roles! (constantly ["admins"])
                  platform/request! (fn [_ _ opts] (swap! calls conj opts) {:body []})]
      (is (= {:assets [] :next-offset nil} (pipeline/library! {:id "admin"} {})))
      (is (not (contains? (:query-params (first @calls)) "or"))))))

(deftest cms-metadata-updates-and-deletion-preserve-published-pixels
  (let [calls (atom [])
        existing {:id "job" :status "ready" :policy_version pipeline/policy-version :source_url "https://cms.test/uploads/a.png"
                  :metadata {:external-hash "unchanged"} :asset asset}]
    (with-redefs [pipeline/enabled? (constantly true)
                  content/settings (constantly {:url "https://cms.test"})
                  platform/request! (fn [method _ opts]
                                      (swap! calls conj [method opts])
                                      {:body (when (= method :get) [existing])})
                  cms/source-bytes! (fn [& _] (throw (ex-info "Metadata update downloaded pixels" {})))]
      (let [result (cms/intake! {:id 1 :url "/uploads/a.png" :hash "unchanged" :alternativeText "New alt"})]
        (is (= "ready" (:status result)))
        (is (= "New alt" (get-in result [:asset :alt])))
        (is (= (:src asset) (get-in result [:asset :src]))))
      (reset! calls [])
      (cms/delete! {:id 1})
      (is (= "in.(queued,processing)" (get-in (first @calls) [1 :query-params "status"])))
      (is (false? (get-in (second @calls) [1 :form-params :active])))
      (is (nil? (get-in (second @calls) [1 :form-params :source_url])))
      (is (every? #(= :patch (first %)) @calls)))))

(deftest real-cms-download-pins-origin-and-rejects-redirects
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/uploads"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (if (= "/uploads/redirect.png" (.getPath (.getRequestURI exchange)))
              (do (.add (.getResponseHeaders exchange) "Location" "https://other.test/uploads/a.png")
                  (.sendResponseHeaders exchange 302 -1))
              (do (.sendResponseHeaders exchange 200 (alength source-bytes))
                  (with-open [output (.getResponseBody exchange)] (.write output source-bytes))))
            (finally (.close exchange))))))
    (.start server)
    (try
      (with-redefs [content/settings (constantly {:url (str "http://127.0.0.1:" (.getPort (.getAddress server)))})
                    config/env {:media {:allow-private-cms? true}}]
        (is (= (seq source-bytes) (seq (cms/source-bytes! (cms/source-uri! "/uploads/a.png")))))
        (is (thrown? clojure.lang.ExceptionInfo (cms/source-bytes! (cms/source-uri! "/uploads/redirect.png")))))
      (finally (.stop server 0)))))

(deftest real-jvm-rendition-owner-validates-manifest-and-cleans-files
  (let [directory (atom nil)
        bytes (with-open [output (ByteArrayOutputStream.)]
                (ImageIO/write (BufferedImage. 240 160 BufferedImage/TYPE_INT_RGB) "png" output)
                (.toByteArray output))
        result (image/with-renditions! bytes "png"
                 (fn [path manifest]
                   (reset! directory path)
                   (is (m/validate (dsl/->malli image/rendition-manifest) manifest))
                   (is (= [240] (mapv :width (:avif manifest))))
                   (doseq [rendition (concat [(:original manifest)] (:webp manifest) (:avif manifest))]
                     (is (.isFile (io/file path (:file rendition)))))
                   :complete))]
    (is (= :complete result))
    (is (not (.exists @directory)))
    (is (thrown? Exception
                 (image/with-renditions! bytes "png" (fn [path _] (reset! directory path) (throw (Exception. "publish failed"))))))
    (is (not (.exists @directory)))))

(deftest jsonb-url-keys-remain-exact-strings-after-json-keywordization
  (let [row (json/read-str (json/write-str {:image_assets {(:src asset) asset}}) :key-fn keyword)
        normalized (query/normalize-row row)]
    (is (= {(:src asset) asset} (:image_assets normalized)))
    (is (= normalized (query/normalize-row normalized)))))

(deftest cms-svg-manifests-enrich-media-props-and-string-logos
  (let [source "https://cms.test/uploads/logo.svg?version=2"
        original {:src "https://storage.test/immutable/logo.svg"
                  :width 1600
                  :height 900
                  :bytes 1000
                  :mime "image/svg+xml"}
        vector-asset {:id "svg-logo"
                      :src (:src original)
                      :width 1600
                      :height 900
                      :renditions {:original original}}]
    (with-redefs [pipeline/assets! (fn [sources]
                                    (is (= #{source} (set sources)))
                                    {source vector-asset})]
      (let [enriched (content/enrich-media!
                      {:cv {:logo source
                            :heading {:bg {:src source, :alt "Author title"}}}})]
        (is (= source (get-in enriched [:cv :logo])))
        (is (= vector-asset (get-in enriched [:cv :image-assets source])))
        (is (= vector-asset (get-in enriched [:cv :heading :bg :asset])))
        (is (= (:src original) (get-in enriched [:cv :heading :bg :src])))
        (is (= "Author title" (get-in enriched [:cv :heading :bg :alt])))))))

(deftest comment-image-upload-does-not-grant-library-access
  (let [file (.toFile (Files/createTempFile "comment-upload-test" ".png" (make-array java.nio.file.attribute.FileAttribute 0)))
        queued (atom nil)
        user {:id "commenter"}]
    (try
      (with-open [output (io/output-stream file)] (.write output source-bytes))
      (with-redefs [site/enabled? (constantly true)
                    pipeline/configured! (constantly nil)
                    pipeline/roles! (constantly [])
                    auth/ensure-profile! (constantly "commenter")
                    pipeline/queue-bytes! (fn [bytes metadata]
                                            (is (= (seq source-bytes) (seq bytes)))
                                            (reset! queued metadata)
                                            {:id "comment-job", :status "queued"})]
        (is (= {:id "comment-job", :status "queued"}
               (pipeline/comment-upload! user {:tempfile file, :size (count source-bytes)})))
        (is (= "commenter" (:owner-id @queued)))
        (is (= "comment-upload" (get-in @queued [:metadata :purpose])))
        (is (= 403 (:auth/status (ex-data (try (pipeline/library! user {})
                                             (catch clojure.lang.ExceptionInfo error error))))))
        (is (= 400 (:auth/status (ex-data (try (pipeline/comment-upload! user {:tempfile file, :size 0})
                                             (catch clojure.lang.ExceptionInfo error error)))))))
      (with-redefs [site/enabled? (constantly false)]
        (is (= 404 (:auth/status (ex-data (try (pipeline/comment-upload! user {})
                                             (catch clojure.lang.ExceptionInfo error error)))))))
      (finally (.delete file)))))

(deftest registered-comment-upload-route-does-not-conflict-with-owned-job-polling
  (with-redefs [pipeline/start! (constantly nil)]
    (let [router (ring/router (main-api/routes))
          upload (reitit/match-by-path router "/supabase/comment-media")
          polling (reitit/match-by-path router "/supabase/media/owned-job")]
      (is (= "/supabase/comment-media" (:template upload)))
      (is (get-in upload [:data :post :handler]))
      (is (nil? (get-in upload [:data :get])))
      (is (= "/supabase/media/:id" (:template polling)))
      (is (get-in polling [:data :get :handler])))))
