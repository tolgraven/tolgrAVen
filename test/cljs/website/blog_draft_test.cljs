(ns website.blog-draft-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader :as reader]
            [website.react :as r]
            [website.component.storage :as storage]
            [website.modules.blog-editor.draft :as draft]
            [website.test-support :as support]
            [website.blog-editor-test :as editor-test]))

(deftest failed-private-reads-preserve-the-envelope-and-current-input-until-retry
  (async done
    (let [before @r/*app-db
          identity @storage/*identity
          account [(str (random-uuid)) 995]
          key (str storage/prefix (pr-str account))
          read-disk! storage/read-disk!
          owner "draft-read-failure"
          form {:title "Saved post", :text "Original saved content", :tags "", :image-assets {}}
          envelope (pr-str
                     (into {} (map (fn [[id value]]
                                     [id {:version 1, :schema 1, :expires-at (+ (.now js/Date) 60000), :value value}]))
                       [[[:blog-editor/working :new] {:owner-id 995, :document-id "saved-id", :named? true}]
                        [draft/collection-id {:owner-id 995, :entries {"saved-id" {:id "saved-id", :form form}}}]]))
          status #(get-in @r/*app-db [:state :blog-editor :draft :status])
          forget-read! (fn [] (swap! storage/*reads dissoc account)
                        (swap! storage/*ready disj account) (swap! storage/*buckets dissoc account))]
      (.setItem (.-localStorage js/window) key envelope)
      (set! storage/read-disk! (fn [storage-key]
                                (if (= key storage-key) (throw (js/Error. "getItem failed")) (read-disk! storage-key))))
      (storage/identity! account)
      (r/dispatch-sync [:state [:active-user] {:id 995}])
      (r/dispatch-sync [:blog/editor-mounted owner])
      (r/dispatch-sync [:form-field [:post-blog] {:title "Current", :text "Keep my typing", :tags ""}])
      (-> (go-promise
            (r/dispatch-sync [:blog/load-draft owner])
            (await! (support/wait-for! #(= :failed (status))))
            (is (= "Keep my typing" (get-in @r/*app-db (conj draft/form-path :text))))
            (storage/flush! :navigation)
            (storage/drain!)
            (is (= envelope (.getItem (.-localStorage js/window) key)))
            (set! storage/read-disk! read-disk!)
            (r/dispatch-sync [:blog/load-draft owner])
            (await! (support/wait-for! #(= :idle (status))))
            (is (= "Keep my typing" (get-in @r/*app-db (conj draft/form-path :text))))
            (is (= form (get-in @r/*app-db [:state :blog-editor :draft :entries "saved-id" :form])))
            (storage/untrack! [:blog-editor/working :new])
            (storage/untrack! draft/collection-id)
            (forget-read!)
            (.setItem (.-localStorage js/window) key "{:unfinished")
            (r/dispatch-sync [:form-field [:post-blog] nil])
            (r/dispatch-sync [:blog/load-draft owner])
            (await! (support/wait-for! #(= :failed (status))))
            (storage/flush! :navigation)
            (storage/drain!)
            (is (= "{:unfinished" (.getItem (.-localStorage js/window) key)))
            (.setItem (.-localStorage js/window) key envelope)
            (r/dispatch-sync [:blog/load-draft owner])
            (await! (support/wait-for! #(= :restored (status))))
            (is (= "Original saved content" (get-in @r/*app-db (conj draft/form-path :text)))))
          (.catch #(is false (str % " status=" (status))))
          (.finally (fn [] (set! storage/read-disk! read-disk!)
                      (storage/untrack! [:blog-editor/working :new]) (storage/untrack! draft/collection-id)
                      (storage/identity! identity) (swap! storage/*read-errors disj account)
                      (.removeItem (.-localStorage js/window) key)
                      (editor-test/restore-fixture-db! before) (done)))))))

(deftest late-disk-results-do-not-replace-new-input-a-different-account-or-a-closed-editor
  (let [before @r/*app-db
        owner "draft-race"
        state {:owner owner, :account 991, :token "read-token", :status :loading}
        working {:owner-id 991, :document-id "saved-document", :named? false
                 :form {:title "Old", :text "Old disk content", :tags "", :image-assets {}}}
        result [:blog/drafts-read owner 991 "read-token" [:blog-editor/working :new] {}
                true "new-document" working nil]]
    (try
      (with-redefs [storage/track! (fn [& _] nil), storage/schedule! (fn [] nil)]
        (r/dispatch-sync [:state [:active-user] {:id 991}])
        (r/dispatch-sync [:blog/editor-mounted owner])
        (r/dispatch-sync [:state [:blog-editor :draft] state])
        (r/dispatch-sync [:form-field [:post-blog] {:title "New", :text "Typed during the read", :tags ""}])
        (r/dispatch-sync result)
        (is (= "Typed during the read" (get-in @r/*app-db [:state :form-field :post-blog :text])))
        (r/dispatch-sync [:state [:blog-editor :draft] state])
        (r/dispatch-sync [:state [:active-user] {:id 992}])
        (r/dispatch-sync result)
        (is (= "Typed during the read" (get-in @r/*app-db [:state :form-field :post-blog :text])))
        (r/dispatch-sync [:state [:active-user] {:id 991}])
        (r/dispatch-sync [:blog/editor-mounted "different-owner"])
        (r/dispatch-sync result)
        (is (= "Typed during the read" (get-in @r/*app-db [:state :form-field :post-blog :text]))))
      (finally (editor-test/restore-fixture-db! before)))))

(deftest working-and-named-drafts-use-durable-private-storage-and-preserve-account-boundaries
  (async done
    (let [before @r/*app-db
          identity @storage/*identity
          account [(str (random-uuid)) 991]
          key (str storage/prefix (pr-str account))
          owner "draft-storage-A"
          write-disk! storage/write-disk!
          disk #(some-> (.getItem (.-localStorage js/window) key) reader/read-string)
          saved #(get-in (disk) [[:blog-editor/working :new] :value])
          ready? #(not= :loading (get-in @r/*app-db [:state :blog-editor :draft :status]))]
      (storage/identity! account)
      (r/dispatch-sync [:state [:active-user] {:id 991}])
      (r/dispatch-sync [:form-field [:post-blog] nil])
      (r/dispatch-sync [:blog/editor-mounted owner])
      (-> (go-promise
            (r/dispatch-sync [:blog/load-draft owner])
            (await! (support/wait-for! ready?))
            (r/dispatch-sync [:form-field [:post-blog :title] "First post"])
            (r/dispatch-sync [:form-field [:post-blog :text] "Autosaved **working** text"])
            (r/dispatch-sync [:form-field [:post-blog :tags] "writing, drafts"])
            (await! (support/wait-for! #(= "Autosaved **working** text" (get-in (saved) [:form :text]))))
            (let [id (:document-id (saved))]
              (r/dispatch-sync [:blog/save-draft owner])
              (await! (support/wait-for! #(= :saved (get-in @r/*app-db [:state :blog-editor :draft :status]))))
              (is (= id (:document-id (saved))))
              (is (nil? (:form (saved))) "The active named draft is referenced, not duplicated")
              (r/dispatch-sync [:blog/new-draft owner])
              (r/dispatch-sync [:form-field [:post-blog :title] "Second post"])
              (r/dispatch-sync [:form-field [:post-blog :text] "Second draft"])
              (r/dispatch-sync [:blog/save-draft owner])
              (await! (support/wait-for! #(= :saved (get-in @r/*app-db [:state :blog-editor :draft :status]))))
              (r/dispatch-sync [:blog/select-draft owner id])
              (is (= "Autosaved **working** text" (get-in @r/*app-db [:state :form-field :post-blog :text])))
              (r/dispatch-sync [:blog/editor-text-changed owner "Late old document input" nil nil "different-document"])
              (is (= "Autosaved **working** text" (get-in @r/*app-db [:state :form-field :post-blog :text]))
                  "Queued callbacks from another native document are rejected")
              (r/dispatch-sync [:form-field [:post-blog :text] "Latest before navigation"])
              ;; The actual authoring route-stop event must persist accepted
              ;; input before its queued form clear; do not flush in the test.
              (r/dispatch-sync [:blog/cancel-edit])
              (await! (support/wait-for! #(nil? (get-in @r/*app-db draft/form-path))))
              (is (= "Latest before navigation" (get-in (disk) [draft/collection-id :value :entries id :form :text])))
              (storage/untrack! [:blog-editor/working :new])
              (storage/untrack! draft/collection-id)
              (swap! storage/*buckets dissoc account)
              (swap! storage/*reads dissoc account)
              (swap! storage/*ready disj account)
              (r/dispatch-sync [:form-field [:post-blog] nil])
              (r/dispatch-sync [:blog/editor-mounted "draft-storage-reload"])
              (r/dispatch-sync [:blog/load-draft "draft-storage-reload"])
              (await! (support/wait-for! ready?))
              (is (= id (get-in @r/*app-db [:state :blog-editor :draft :document-id])))
              (is (= "Latest before navigation" (get-in @r/*app-db [:state :form-field :post-blog :text])))
              (is (= 2 (count (get-in @r/*app-db [:state :blog-editor :draft :entries]))))
              (set! storage/write-disk! (fn [storage-key value]
                                         (if (= key storage-key) (throw (js/Error. "Storage unavailable"))
                                             (write-disk! storage-key value))))
              (r/dispatch-sync [:form-field [:post-blog :text] "Retry must save this latest value"])
              (r/dispatch-sync [:blog/save-draft "draft-storage-reload"])
              (await! (support/wait-for! #(= :failed (get-in @r/*app-db [:state :blog-editor :draft :status]))))
              (is (= "Latest before navigation" (get-in (disk) [draft/collection-id :value :entries id :form :text])))
              (set! storage/write-disk! write-disk!)
              (r/dispatch-sync [:blog/save-draft "draft-storage-reload"])
              (await! (support/wait-for! #(= :saved (get-in @r/*app-db [:state :blog-editor :draft :status]))))
              (is (= "Retry must save this latest value" (get-in (disk) [draft/collection-id :value :entries id :form :text])))
              (storage/identity! [(first account) 992])
              (r/dispatch-sync [:state [:active-user] {:id 992}])
              (r/dispatch-sync [:blog/editor-mounted "draft-storage-B"])
              (r/dispatch-sync [:blog/load-draft "draft-storage-B"])
              (await! (support/wait-for! ready?))
              (is (empty? (get-in @r/*app-db [:state :form-field :post-blog :text])))
              (is (empty? (get-in @r/*app-db [:state :blog-editor :draft :entries])))))
          (.catch #(is false (str % " state=" (get-in @r/*app-db [:state :blog-editor :draft])
                                  " form=" (get-in @r/*app-db draft/form-path)
                                  " projected=" (draft/projection @r/*app-db owner)
                                  " saved=" (saved)
                                  " owner=" @storage/*identity
                                  " tracked=" (keys @storage/*tracked))))
          (.finally (fn []
                      (set! storage/write-disk! write-disk!)
                      (storage/untrack! [:blog-editor/working :new])
                      (storage/untrack! draft/collection-id)
                      (storage/identity! identity)
                      (.removeItem (.-localStorage js/window) key)
                      (.removeItem (.-localStorage js/window) (str storage/prefix (pr-str [(first account) 992])))
                      (editor-test/restore-fixture-db! before)
                      (done)))))))
