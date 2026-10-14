(ns website.content-editor-test
  (:require [clojure.test :refer :all]
            [clj-http.client :as http]
            [website.content.editor :as editor]
            [website.content.editor-contract :as contract]
            [website.content.contract :as content-contract]
            [website.content.service :as content]
            [website.supabase.auth :as auth]
            [website.media.pipeline :as media]
            [website.validation :as validation]))

(deftest writes-require-a-verified-administrator
  (let [called (atom false)]
    (with-redefs [auth/current-user! (fn [_] (throw (ex-info "Sign in" {:auth/status 401})))
                  media/admin! (fn [_] (throw (ex-info "Administrator required" {:auth/status 403})))]
      (is (= 401 (:status (editor/response! {} #(reset! called true)))))
      (with-redefs [auth/current-user! (constantly {:id "user"})]
        (is (= 403 (:status (editor/response! {} #(reset! called true))))))
      (is (false? @called)))))

(deftest field-request-contract
  (is (nil? (validation/explain contract/write-request
              {:path ["intro" "text"], :expected "Before", :value "After"})))
  (is (nil? (validation/explain contract/write-request
              {:path ["intro" "bg" 0 "src"], :expected nil, :value "/image.png"})))
  (doseq [request [{:path ["intro"], :expected "old", :value "new"}
                   {:path ["intro" -1], :expected "old", :value "new"}
                   {:path ["intro" "text"], :expected "old", :value {:unsafe true}}
                   {:path ["intro" "text"], :expected "old", :value "new", :token "private"}]]
    (is (some? (validation/explain contract/write-request request)))))

(deftest save-is-bounded-and-installs-the-authoritative-section
  (let [snapshot @content/*immediate
        cache @content/*cache
        observed (atom nil)
        bundle {:version 1, :content {:intro {:title "Saved", :text "Text", :buttons []}}}]
    (try
      (reset! content/*immediate {:source "https://cms.invalid", :content {:intro {:title "Before"}}})
      (reset! content/*cache {"https://cms.invalid" {:intro {:value {:title "Before"}}}})
      (with-redefs [editor/settings (constantly {:url "https://cms.invalid", :token "fixture-write"})
                    content/settings (constantly {:url "https://cms.invalid"})
                    content/enrich-media! identity
                    http/put (fn [url options] (reset! observed [url options]) {:status 200, :body bundle})]
        (is (= bundle (editor/save! {:path ["intro" "title"], :expected "Before", :value "Saved"})))
        (is (= "https://cms.invalid/api/site-content/field" (first @observed)))
        (is (= :none (:redirect-strategy (second @observed))))
        (is (= 15000 (:socket-timeout (second @observed))))
        (is (= "Saved" (get-in @content/*immediate [:content :intro :title])))
        (is (= "Saved" (get-in @content/*cache ["https://cms.invalid" :intro :value :title]))))
      (finally (reset! content/*immediate snapshot) (reset! content/*cache cache)))))

(deftest upstream-errors-do-not-expose-credentials
  (with-redefs [auth/current-user! (constantly {:id "admin"})
                media/admin! (constantly nil)
                editor/settings (constantly {:url "https://cms.invalid", :token "fixture-sensitive-write-token"})
                content/install-edited! (constantly nil)
                http/put (fn [& _] {:status 409, :body {:version 1, :content {:intro {:title "Latest"}}}})
                content/enrich-media! identity]
    (let [response (editor/response! {} #(editor/save! {:path ["intro" "title"], :expected "Before", :value "After"}))]
      (is (= 409 (:status response)))
      (is (not (.contains (pr-str response) "fixture-sensitive-write-token")))))
  (with-redefs [auth/current-user! (constantly {:id "admin"})
                media/admin! (constantly nil)]
    (is (= {:status 503
            :headers {"Cache-Control" "private, no-store"}
            :body {:error "Content editing is temporarily unavailable"}}
           (editor/response! {} #(throw (ex-info "Authorization: private" {})))))))

(deftest cv-creation-is-bounded-and-uses-the-private-write-adapter
  (let [entry {:kind "regular", :position "", :from 2020, :to nil, :what "Role", :where "Organisation", :how ["Detail"]}
        request {:column 0, :category "work", :parents [], :parent-keys [], :expected-count 3, :entry entry}
        observed (atom nil)
        bundle {:version 1, :content {:cv {:title "CV"}}}]
    (is (nil? (validation/explain contract/create-cv-entry request)))
    (doseq [invalid [(assoc request :column -1)
                     (assoc request :expected-count 1000)
                     (assoc-in request [:entry :to] 2019)
                     (assoc-in request [:entry :how] [(apply str (repeat 2001 "x"))])
                     (assoc-in request [:entry :id] 123)]]
      (is (some? (validation/explain contract/create-cv-entry invalid))))
    (with-redefs [editor/settings (constantly {:url "https://cms.invalid", :token "fixture-write"})
                  content/enrich-media! identity
                  content/install-edited! #(reset! observed %)
                  http/post (fn [url options]
                              (is (= "https://cms.invalid/api/site-content/cv-entry" url))
                              (is (= request (:form-params options)))
                              (is (= :none (:redirect-strategy options)))
                              {:status 200, :body bundle})]
      (is (= (content-contract/checked-bundle bundle [:cv]) (editor/create-cv! request)))
      (is (= (:content (content-contract/checked-bundle bundle [:cv])) @observed)))))
