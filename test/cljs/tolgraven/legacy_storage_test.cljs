(ns tolgraven.legacy-storage-test
  (:require [cljs.test :refer-macros [deftest is]]
            [tolgraven.component.legacy-storage :as legacy]
            [tolgraven.component.storage :as storage]
            [tolgraven.service-status :as status]))

(deftest original-transit-key-and-values-survive
  ;; Literal disk fixtures from storage-atom's Transit JSON writer. EDN or a
  ;; plain "state" key would lose cookie consent and existing form drafts.
  (is (= "[\"~#'\",\"~:state\"]" legacy/disk-key))
  (let [saved "[\"^ \",\"~:cookie-notice-accepted\",true,\"~:form-field\",[\"^ \",\"~:comment\",\"draft\"]]"
        value {:cookie-notice-accepted true :form-field {:comment "draft"}}]
    (is (= value (legacy/decode saved)))
    (is (= value (legacy/decode (legacy/encode value))))))

(deftest reads-once-and-consolidates-migration-and-consent-writes
  (let [reads (atom []) writes (atom [])
        initial {:cookie-notice-accepted true :blog {:comments-expanded #{12}}}]
    (with-redefs [legacy/*state (atom nil)
                  legacy/*loaded? (atom false)
                  legacy/*pending (atom nil)
                  storage/read-disk! (fn [key] (swap! reads conj key) (legacy/encode initial))
                  storage/write-disk! (fn [key text] (swap! writes conj [key (legacy/decode text)]))]
      (try
        (is (= initial (legacy/read!)))
        (legacy/write! (dissoc (legacy/read!) :blog))
        (legacy/write! (assoc (legacy/read!) :form-field {:comment "new draft"}))
        (is (= {:cookie-notice-accepted true :form-field {:comment "new draft"}}
               (legacy/read!)))
        (is (= [legacy/disk-key] @reads))
        (is (empty? @writes))
        (legacy/drain!)
        (is (= [[legacy/disk-key {:cookie-notice-accepted true
                                 :form-field {:comment "new draft"}}]] @writes))
        (legacy/write! (legacy/read!))
        (legacy/drain!)
        (is (= 1 (count @writes)))
        (finally (legacy/drain!))))))

(deftest storage-events-restore-clear-without-writing-back
  (with-redefs [legacy/*state (atom {:cookie-notice-accepted true})
                legacy/*loaded? (atom true)
                legacy/*pending (atom nil)]
    (let [event (fn [key value]
                  #js {:storageArea (.-localStorage js/window) :key key :newValue value})]
      (legacy/storage-event! (event "unrelated" "invalid"))
      (is (= {:cookie-notice-accepted true} (legacy/read!)))
      (legacy/storage-event! (event legacy/disk-key (legacy/encode {:form-field {:comment "other tab"}})))
      (is (= {:form-field {:comment "other tab"}} (legacy/read!)))
      (legacy/storage-event! (event legacy/disk-key "invalid transit"))
      (is (= {:form-field {:comment "other tab"}} (legacy/read!)))
      (legacy/storage-event! (event nil nil))
      (is (nil? (legacy/read!)))
      (is (nil? @legacy/*pending)))))

(deftest unavailable-or-malformed-storage-keeps-memory-usable
  (doseq [read-disk [(fn [_] (throw (js/Error. "Storage blocked")))
                   (fn [_] "invalid transit")]]
    (let [failures (atom 0)]
      (with-redefs [legacy/*state (atom nil)
                    legacy/*loaded? (atom false)
                    legacy/*pending (atom nil)
                    storage/read-disk! read-disk
                    storage/write-disk! (fn [_ _] (throw (js/Error. "Quota exceeded")))
                    status/fail! (fn [& _] (swap! failures inc))]
        (is (nil? (legacy/read!)))
        (legacy/write! {:form-field {:comment "unsaved draft"}})
        (legacy/drain!)
        (is (= {:form-field {:comment "unsaved draft"}} (legacy/read!)))
        (is (nil? @legacy/*pending))
        (is (= 1 @failures))))))

(deftest cross-tab-replacement-cancels-pending-write
  (doseq [[key text expected] [[legacy/disk-key (legacy/encode {:cookie-notice-accepted true})
                               {:cookie-notice-accepted true}]
                              [nil nil nil]]]
    (let [writes (atom [])]
      (with-redefs [legacy/*state (atom {})
                    legacy/*loaded? (atom true)
                    legacy/*pending (atom nil)
                    storage/write-disk! (fn [& args] (swap! writes conj args))]
        (legacy/write! {:form-field {:comment "local draft"}})
        (legacy/storage-event! #js {:storageArea (.-localStorage js/window)
                                   :key legacy/disk-key :newValue "invalid transit"})
        (is (some? @legacy/*pending))
        (legacy/storage-event! #js {:storageArea (.-localStorage js/window)
                                   :key key :newValue text})
        (is (= expected (legacy/read!)))
        (is (nil? @legacy/*pending))
        (legacy/drain!)
        (is (empty? @writes))))))
