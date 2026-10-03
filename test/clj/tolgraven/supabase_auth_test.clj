(ns tolgraven.supabase-auth-test
  (:require [clojure.test :refer :all]
            [clj-http.client :as http]
            [tolgraven.platform.supabase :as platform]
            [tolgraven.supabase.auth :as auth]
            [tolgraven.provision.supabase.auth :as provision-auth]))

(deftest missing-or-invalid-sessions-never-touch-profile-data
  (with-redefs [platform/request! (fn [& _] (throw (Exception. "Unexpected profile access")))]
    (doseq [header [nil "" "Basic abc" "Bearer a b"]]
      (is (= 401 (:status (auth/response! {:headers {"authorization" header}} auth/profile!))))))
  (with-redefs [platform/service-key (constantly "server-key")
                platform/rest-base-url (constantly "https://example.invalid")
                http/get (fn [_ _] {:status 401})]
    (is (= 401 (:status (auth/response! {:headers {"authorization" "Bearer expired"}} auth/profile!))))))

(deftest session-is-verified-with-auth-server
  (let [seen (atom nil)]
    (with-redefs [platform/service-key (constantly "server-key")
                  platform/rest-base-url (constantly "https://example.invalid/")
                  http/get (fn [url opts]
                             (reset! seen [url opts])
                             {:status 200 :body {:id "account-1"}})]
      (is (= "account-1" (:id (auth/current-user! {:headers {"authorization" "Bearer access-token"}}))))
      (is (= "https://example.invalid/auth/v1/user" (first @seen)))
      (is (= "Bearer access-token" (get-in @seen [1 :headers "Authorization"])))
      (is (= "server-key" (get-in @seen [1 :headers "apikey"]))))))

(deftest only-administrator-metadata-can-link-a-legacy-profile
  (is (= "account-1" (auth/profile-id {:id "account-1"
                                      :user_metadata {:site_user_id "victim"}})))
  (is (= "legacy-1" (auth/profile-id {:id "account-1"
                                     :app_metadata {:site_user_id "legacy-1"}})))
  (is (thrown? clojure.lang.ExceptionInfo
               (auth/profile-id {:id "account-1" :app_metadata {:site_user_id "bad,id"}}))))

(deftest profile-writes-are-owned-and-field-scoped
  (let [calls (atom [])]
    (with-redefs [platform/request! (fn [method table opts]
                                     (swap! calls conj [method table opts])
                                     {:body []})]
      (auth/save-profile! {:id "account-1" :app_metadata {:site_user_id "legacy-1"}}
                          {:name "Updated"})
      (is (= "site_users" (get-in @calls [0 1])))
      (is (= {:id "legacy-1" :name "Updated"} (get-in @calls [0 2 :form-params])))
      (reset! calls [])
      (doseq [data [{:id "victim"} {:karma 9000} {:email "other@example.com"}
                    {:raw {}} {:name {:nested "value"}}]]
        (is (thrown? clojure.lang.ExceptionInfo (auth/save-profile! {:id "account-1"} data))))
      (is (empty? @calls)))))


(deftest legacy-linking-requires-confirmed-matching-email
  (let [updates (atom [])
        account-id "12345678-1234-1234-1234-123456789abc"
        account (atom {:id account-id :email "owner@example.com"
                       :email_confirmed_at "2026-10-03"
                       :app_metadata {:provider "email"}})]
    (with-redefs [platform/service-key (constantly "server-key")
                  platform/rest-base-url (constantly "https://example.invalid")
                  platform/request! (fn [& _] {:body [{:id "legacy" :email "owner@example.com"}]})
                  http/request (fn [opts]
                                 (when (= :put (:method opts)) (swap! updates conj opts))
                                 {:status 200 :body @account})]
      (is (:linked? (provision-auth/link-user! account-id "legacy")))
      (is (= {:provider "email" :site_user_id "legacy"}
             (get-in @updates [0 :form-params :app_metadata])))
      (reset! updates [])
      (doseq [user [{:email "attacker@example.com" :email_confirmed_at "confirmed"}
                    {:email "owner@example.com"}
                    {:email "owner@example.com" :email_confirmed_at "confirmed"
                     :app_metadata {:site_user_id "other"}}]]
        (reset! account user)
        (is (thrown? clojure.lang.ExceptionInfo
                     (provision-auth/link-user! account-id "legacy"))))
      (is (empty? @updates)))))
