(ns tolgraven.provision.supabase.auth-import
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption StandardOpenOption]
           [java.nio.file.attribute PosixFilePermissions]
           [java.time Instant]
           [java.util Base64 UUID]))

(def provider-names {"password" "email" "google.com" "google"
                     "github.com" "github" "facebook.com" "facebook"})

(defn- require! [condition message]
  ;; Do not attach source records to exceptions: they contain password material.
  (when-not condition (throw (ex-info message {}))))

(defn- canonical-base64 [value]
  (require! (and (string? value) (seq value)
                (re-matches #"[A-Za-z0-9+/=_-]+" value)) "Missing or invalid hash material")
  (try
    (.encodeToString (Base64/getEncoder)
                     (.decode (Base64/getDecoder) (string/replace value #"[-_]" {"-" "+" "_" "/"})))
    (catch IllegalArgumentException _ (throw (ex-info "Invalid base64 hash material" {})))))

(defn firebase-password-hash [user config]
  (when-let [password-hash (:passwordHash user)]
    (let [{:keys [algorithm signerKey saltSeparator rounds memoryCost]} config
          hash (canonical-base64 password-hash)
          salt (canonical-base64 (:salt user))
          signer (canonical-base64 signerKey)
          separator (canonical-base64 saltSeparator)]
      (require! (= "SCRYPT" algorithm) "Only Firebase SCRYPT hashes are supported")
      (require! (and (integer? memoryCost) (<= 1 memoryCost 20)
                    (integer? rounds) (<= 1 rounds 32)) "Invalid Firebase SCRYPT parameters")
      (require! (= 64 (alength (.decode (Base64/getDecoder) signer)))
                "Invalid Firebase signer key")
      (require! (= 64 (alength (.decode (Base64/getDecoder) hash)))
                "Missing, redacted or invalid Firebase password hash")
      (str "$fbscrypt$v=1,n=" memoryCost ",r=" rounds ",p=1,ss="
           separator ",sk=" signer
           "$" salt "$" hash))))

(defn account-id [project-id firebase-id]
  (str (UUID/nameUUIDFromBytes
         (.getBytes (str "tolgraven.firebase/" project-id "/" firebase-id) StandardCharsets/UTF_8))))

(defn- timestamp [value fallback]
  (if (seq (str value))
    (try (str (Instant/ofEpochMilli (Long/parseLong (str value))))
         (catch Exception _ (throw (ex-info "Invalid Firebase account timestamp" {}))))
    fallback))

(defn- identities [user id]
  (let [providers (:providerUserInfo user)]
    (require! (seq providers) "Account without supported provider requires manual migration")
    (mapv
      (fn [source]
        (let [provider (get provider-names (:providerId source))
              subject (if (= provider "email") id (:rawId source))
              email (some-> (or (:email source) (:email user)) string/lower-case)]
          (require! provider "Unsupported Firebase identity provider")
          (require! (and (string? subject) (seq subject)) "Provider identity is missing its subject")
          {:provider provider :provider_id subject
           :identity_data (cond-> {:sub subject :email_verified (true? (:emailVerified user))
                                  :firebase_uid (:localId user)}
                            email (assoc :email email)
                            (:displayName source) (assoc :name (:displayName source))
                            (:photoUrl source) (assoc :avatar_url (:photoUrl source)))}))
      providers)))

(defn prepare-import [export config]
  (let [project (:projectId export)
        now (or (:exportedAt export) (str (Instant/now)))
        hash-config (or (get-in config [:signIn :hashConfig]) (:hashConfig config) config)
        users (:users export)]
    (require! (and (string? project) (re-matches #"[a-zA-Z0-9_-]+" project))
              "Auth export must include its Firebase projectId")
    (when-let [name (:name config)]
      (require! (contains? (set (map #(str "projects/" % "/config")
                                    (keep identity [project (:projectNumber export)]))) name)
                "Hash configuration belongs to a different Firebase project"))
    (require! (and (vector? users) (seq users)) "Auth export must contain users")
    (let [accounts
          (mapv (fn [user]
                  (let [uid (:localId user)
                        _ (require! (and (string? uid) (re-matches #"[A-Za-z0-9_-]+" uid))
                                    "Invalid Firebase account ID")
                        _ (require! (not (or (seq (:mfaInfo user)) (:tenantId user)))
                                    "Tenant and MFA accounts require separate migration")
                        id (account-id project uid)
                        identities (identities user id)
                        password (firebase-password-hash user hash-config)
                        email (some-> (:email user) string/lower-case)
                        created (timestamp (:createdAt user) now)]
                    (require! (or (not-any? #(= "email" (:provider %)) identities)
                                  (and (seq email) password)) "Password account is missing email or usable hash")
                    {:id id :firebase_uid uid :email email :encrypted_password password
                     :created_at created :updated_at now
                     :last_sign_in_at (timestamp (:lastLoginAt user) nil)
                     :email_confirmed_at (when (true? (:emailVerified user)) created)
                     :banned_until (when (true? (:disabled user)) "9999-12-31T00:00:00Z")
                     :app_metadata {:provider (:provider (first identities))
                                    :providers (mapv :provider identities)
                                    :site_user_id uid :firebase_uid uid :firebase_project_id project
                                    :firebase_custom_claims (:customAttributes user)}
                     :user_metadata (cond-> {:firebase_uid uid}
                                      (:displayName user) (assoc :name (:displayName user))
                                      (:photoUrl user) (assoc :avatar_url (:photoUrl user)))
                     :name (:displayName user) :avatar (:photoUrl user) :identities identities})) users)
          identities (mapcat :identities accounts)]
      (doseq [[values message] [[(map :firebase_uid accounts) "Duplicate Firebase account ID"]
                                [(keep :email accounts) "Duplicate Firebase account email"]
                                [(map (juxt :provider :provider_id) identities) "Duplicate provider identity"]]]
        (require! (= (count values) (count (distinct values))) message))
      {:project-id project :accounts accounts})))

(defn render-sql [{:keys [accounts]}]
  (str "BEGIN;\nCREATE TEMP TABLE firebase_auth_import (data jsonb NOT NULL) ON COMMIT DROP;\n"
       "INSERT INTO firebase_auth_import VALUES ('"
       (string/replace (json/write-str accounts :escape-slash false) "'" "''") "'::jsonb);\n"
       (slurp (io/resource "supabase/auth-import.sql"))))

(defn write-import-sql! [export-path config-path target]
  (let [prepared (prepare-import (json/read-str (slurp export-path) :key-fn keyword)
                                 (json/read-str (slurp config-path) :key-fn keyword))
        path (.toPath (io/file target))]
    (io/make-parents target)
    (require! (not (Files/exists path (into-array LinkOption [])))
              "Auth import output already exists; choose a fresh private file")
    ;; Set mode at creation, rather than after writing password hashes.
    (Files/createFile path (into-array java.nio.file.attribute.FileAttribute
                                      [(PosixFilePermissions/asFileAttribute
                                         (PosixFilePermissions/fromString "rw-------"))]))
    (Files/write path (.getBytes (render-sql prepared) StandardCharsets/UTF_8)
                 (into-array OpenOption [StandardOpenOption/WRITE]))
    {:accounts (count (:accounts prepared))
     :passwords (count (filter :encrypted_password (:accounts prepared)))
     :output target}))
