(ns tolgraven.middleware.multipart
  "One bounded intake before CSRF; route coercion consumes the parsed fields."
  (:require [clojure.java.io :as io]
            [ring.middleware.multipart-params :as multipart]
            [reitit.coercion :as coercion])
  (:import [java.io File]))

(def max-file-bytes (* 5 1024 1024))
(def max-request-bytes (+ max-file-bytes (* 64 1024)))

(defn wrap-multipart [handler]
  (fn [request]
    (let [*files (atom [])
          store! (fn [{:keys [stream] :as item}]
                   (when (seq @*files)
                     (throw (ex-info "Only one uploaded file is allowed" {})))
                   (let [file (File/createTempFile "tolgraven-upload-" ".tmp")]
                     (swap! *files conj file)
                     (io/copy stream file)
                     (assoc (select-keys item [:filename :content-type])
                            :tempfile file :size (.length file))))]
      (try
        ((multipart/wrap-multipart-params
           handler
           {:store store!
            :max-file-size max-file-bytes
            ;; Ring counts form fields too. Permit CSRF and ordinary metadata,
            ;; while the store independently enforces the single-file contract.
            :max-file-count 16
            :progress-fn (fn [_ bytes-read _ _]
                           (when (> bytes-read max-request-bytes)
                             (throw (ex-info "Multipart request exceeded limits" {}))))})
         request)
        (finally
          (doseq [^File file @*files]
            (io/delete-file file true)))))))

(def coercion-middleware
  ;; Reitit's stock multipart middleware also parses. Keep its public coercion
  ;; extension point, applied after ordinary request coercion, without rereading.
  {:name ::coercion
   :compile (fn [{:keys [parameters coercion]} options]
              (when (:multipart parameters)
                (let [options' (assoc options ::coercion/parameter-coercion
                                      {:multipart (coercion/->ParameterCoercion
                                                    :multipart-params :string true true)})
                      coercers (coercion/request-coercers coercion parameters options')]
                  {:data {:swagger {:consumes ^:replace #{"multipart/form-data"}}}
                   :wrap (fn [handler]
                           (fn [request]
                             (handler (update request :parameters merge
                                              (coercion/coerce-request coercers request)))))})))})
