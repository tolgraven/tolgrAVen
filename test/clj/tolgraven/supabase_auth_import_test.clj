(ns tolgraven.supabase-auth-import-test
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [tolgraven.provision.supabase.auth-import :as auth-import])
  (:import [java.util Base64]))

(def test-hash (.encodeToString (Base64/getUrlEncoder) (byte-array 64)))
(def test-config {:algorithm "SCRYPT" :signerKey test-hash :saltSeparator "Bw=="
                  :rounds 8 :memoryCost 14})
(def password-user {:localId "legacy-owner" :email "Owner@Example.invalid"
                    :passwordHash test-hash :salt "-_8="
                    :createdAt "1600000000000" :lastLoginAt "1600000001000"
                    :providerUserInfo [{:providerId "password" :email "Owner@Example.invalid"}]})
(defn prepare [users]
  (auth-import/prepare-import {:projectId "test-project" :exportedAt "2026-10-03T00:00:00Z"
                              :users users} {:signIn {:hashConfig test-config}}))

(deftest passwords-and-security-flags-are-preserved
  (let [account (first (:accounts (prepare [password-user])))
        disabled (first (:accounts (prepare [(assoc password-user :disabled true :emailVerified true)])))]
    (is (string/starts-with? (:encrypted_password account) "$fbscrypt$v=1,n=14,r=8,p=1,ss=Bw==,sk="))
    (is (string/includes? (:encrypted_password account) "$+/8=$"))
    (is (= "owner@example.invalid" (:email account)))
    (is (nil? (:email_confirmed_at account)))
    (is (nil? (:banned_until account)))
    (is (= "2020-09-13T12:26:40Z" (:email_confirmed_at disabled)))
    (is (= "9999-12-31T00:00:00Z" (:banned_until disabled)))
    (is (= "legacy-owner" (get-in account [:app_metadata :site_user_id])))
    (is (= "test-project" (get-in account [:app_metadata :firebase_project_id])))
    (is (= (:id account) (get-in account [:identities 0 :provider_id])))
    (is (= (:id account) (:id (first (:accounts (prepare [password-user]))))))))

(deftest oauth-accounts-retain-provider-subjects-without-invented-email
  (doseq [[source provider] [["google.com" "google"] ["github.com" "github"] ["facebook.com" "facebook"]]]
    (let [account (first (:accounts (prepare [{:localId "legacy-social" :displayName "O'Brien"
                                              :providerUserInfo [{:providerId source :rawId "oauth-subject"}]}])))]
      (is (nil? (:email account)))
      (is (nil? (:encrypted_password account)))
      (is (= provider (get-in account [:identities 0 :provider])))
      (is (= "oauth-subject" (get-in account [:identities 0 :identity_data :sub])))
      (is (false? (get-in account [:identities 0 :identity_data :email_verified])))
      (is (string/includes? (auth-import/render-sql {:accounts [account]}) "O''Brien")))))

(deftest unsafe-or-incomplete-exports-fail-before-sql-is-written
  (doseq [user [(assoc password-user :passwordHash "UkVEQUNURUQ=")
                (dissoc password-user :salt)
                (dissoc password-user :email)
                (assoc password-user :localId "bad'id")
                (assoc password-user :mfaInfo [{:phoneInfo "+15550000000"}])
                (assoc password-user :tenantId "tenant")
                (assoc password-user :providerUserInfo [{:providerId "unsupported" :rawId "id"}])
                (assoc password-user :providerUserInfo [{:providerId "google.com"}])]]
    (is (thrown? clojure.lang.ExceptionInfo (prepare [user]))))
  (is (thrown? clojure.lang.ExceptionInfo (prepare [password-user password-user])))
  (is (thrown? clojure.lang.ExceptionInfo
               (prepare [password-user (assoc password-user :localId "another")]))))

(deftest imported-hashes-require-firebase-scrypt-parameters
  (doseq [config [(assoc test-config :algorithm "STANDARD_SCRYPT")
                  (dissoc test-config :signerKey)
                  (assoc test-config :memoryCost 0)
                  (assoc test-config :rounds 100)]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (auth-import/firebase-password-hash password-user config)))))


(deftest hash-configuration-matches-project-id-or-verified-project-number
  (let [export {:projectId "test-project" :projectNumber "123456" :users [password-user]}]
    (is (= 1 (count (:accounts (auth-import/prepare-import export
                               {:name "projects/123456/config" :signIn {:hashConfig test-config}})))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (auth-import/prepare-import export
                   {:name "projects/654321/config" :signIn {:hashConfig test-config}})))))
