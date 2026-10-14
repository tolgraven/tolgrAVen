(ns website.storage-disk-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [website.test-support]
            [website.component.storage-disk :as disk]
            [website.component.storage :as storage]))

(defn- forget! [account]
  (doseq [*store [storage/*reads storage/*buckets storage/*consumed storage/*disk-writes
                 storage/*disk-generations storage/*legacy-text]]
    (swap! *store dissoc account))
  (doseq [*store [storage/*ready storage/*dirty storage/*heavy-owners storage/*migrating
                 storage/*legacy-backed storage/*read-errors]]
    (swap! *store disj account))
  (doseq [*store [storage/*heavy-ready storage/*heavy-read-errors]]
    (swap! *store disj account)))

(deftest small-private-storage-remains-usable-when-the-heavy-store-cannot-be-read
  (async done
    (let [account (random-uuid)
          identity @storage/*identity
          options {:scope :user}
          key (str storage/prefix (pr-str account))
          draft [:blog-editor/working :without-idb]
          heavy [:state [:store :unseen]]
          snapshot {:version 1, :schema 1, :expires-at (+ (.now js/Date) 60000), :value "Saved draft"}
          read! disk/read!
          write! disk/write!
          *writes (atom 0)]
      (reset! storage/*identity account)
      (storage/write-disk! key (pr-str {draft snapshot}))
      (set! disk/read! (fn [_] (js/Promise.reject (js/Error. "IndexedDB unavailable"))))
      (set! disk/write! (fn [& _] (swap! *writes inc) (js/Promise.resolve nil)))
      (-> (go-promise
            (await! (storage/ready! options))
            (is (false? (storage/read-failed? options)))
            (is (= "Saved draft" (:value (storage/read! draft (assoc options :consume? false)))))
            (storage/write! draft "Edited draft" options)
            (storage/write! heavy :new options)
            (is (true? (get (storage/drain!) account)))
            (await! (storage/drain-async!))
            (let [saved (await! (disk/read-local! (storage/read-disk! key)))]
              (is (= "Edited draft" (get-in saved [draft :value]))))
            (is (zero? @*writes) "Failed cache reads cannot authorize overwriting unseen IDB data")
            (forget! account)
            (set! disk/read! (fn [_] (js/Promise.resolve {heavy {:version "corrupt"}})))
            (await! (storage/ready! options))
            (is (false? (storage/read-failed? options)) "Invalid heavy snapshots do not invalidate readable drafts")
            (is (= "Edited draft" (:value (storage/read! draft (assoc options :consume? false)))))
            (storage/write! heavy :new options)
            (storage/drain!)
            (await! (storage/drain-async!))
            (is (zero? @*writes) "Invalid heavy snapshots also require a successful read before replacement"))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! disk/read! read!)
                      (set! disk/write! write!)
                      (reset! storage/*identity identity)
                      (forget! account)
                      (.removeItem js/localStorage key)
                      (-> (disk/remove! account) (.finally done))))))))

(deftest incremental-legacy-reader-preserves-tags-and-rejects-corruption
  (async done
    (let [value {[:state [:account (random-uuid)]]
                 {:value {:updated-at (js/Date. 1700000000000), :ids #{:one :two}}}}]
      (-> (disk/read-local! (pr-str value))
          (.then (fn [saved]
                   (is (= value saved) "Incremental reads use the existing EDN tag readers")
                   (-> (disk/read-local! "{:broken [}")
                       (.then (fn [_] (is false "Corrupt data must reject")))
                       (.catch (fn [_] (is true "Reader errors settle the Promise"))))))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest native-indexeddb-roundtrip-preserves-clojure-values-and-yields-frames
  (async done
    (let [account (random-uuid)
          id [:resource {:source :app-db, :path [:content :test]}]
          snapshot {:version 1
                    :schema 1
                    :expires-at (+ (.now js/Date) 60000)
                    :value {:ids #{:one :two}, :by-id {[1 :a] {:id (random-uuid)}}}}
          bucket (into {id snapshot} (map #(vector [:state [:store :test %]] snapshot)) (range 80))
          frame js/requestAnimationFrame
          *frames (atom 0)]
      (set! js/requestAnimationFrame (fn [callback]
                                     (frame (fn [timestamp] (swap! *frames inc) (callback timestamp)))))
      (-> (disk/write! account bucket)
          (.then #(disk/read! account))
          (.then (fn [saved]
                   (is (= bucket saved) "Vector keys, sets, keywords and UUIDs survive native disk IO")
                   (is (pos? @*frames) "Independent entries yield painted frames")))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! js/requestAnimationFrame frame)
                      (-> (disk/remove! account) (.finally done))))))))

(deftest heavy-migration-commits-before-removing-the-legacy-copy
  (async done
    (let [account (random-uuid)
          identity @storage/*identity
          key (str storage/prefix (pr-str account))
          snapshot {:version 1, :schema 1, :expires-at (+ (.now js/Date) 60000), :value {:title "Cached"}}
          bucket {:public-content snapshot, [:state [:settings]] (assoc snapshot :value {:mouse true})}
          write! disk/write!
          *commit (atom nil)]
      (reset! storage/*identity account)
      (storage/write-disk! key (pr-str bucket))
      (set! disk/write! (fn [_ _] (js/Promise. (fn [resolve _] (reset! *commit resolve)))))
      (-> (go-promise
            (await! (storage/ready! {:scope :user}))
            (let [pending (storage/drain-async!)]
              (await! (js/Promise.resolve nil))
              (await! (js/Promise.resolve nil))
              (is (.includes (storage/read-disk! (storage/migration-key account)) ":public-content")
                  "The only durable legacy copy remains while the transaction is pending")
              (is (not (.includes (storage/read-disk! key) ":public-content")))
              (@*commit nil)
              (await! pending)
              (is (not (.includes (storage/read-disk! key) ":public-content")))
              (is (.includes (storage/read-disk! key) ":mouse"))
              (is (nil? (storage/read-disk! (storage/migration-key account))))
              (is (= snapshot (get-in @storage/*buckets [account :public-content])))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! disk/write! write!)
                      (reset! storage/*identity identity)
                      (forget! account)
                      (.removeItem js/localStorage key)
                      (.removeItem js/localStorage (storage/migration-key account))
                      (-> (disk/remove! account) (.finally done))))))))

(deftest failed-heavy-migration-keeps-small-saves-durable-and-removals-absent
  (async done
    (let [account (random-uuid)
          identity @storage/*identity
          options {:scope :user}
          key (str storage/prefix (pr-str account))
          draft [:blog-editor/working :migration-test]
          removed [:state [:settings :removed]]
          snapshot {:version 1, :schema 1, :expires-at (+ (.now js/Date) 60000), :value :old}
          write! disk/write!]
      (reset! storage/*identity account)
      (storage/write-disk! key (pr-str {:public-content snapshot, draft snapshot, removed snapshot}))
      (set! disk/write! (fn [& _] (js/Promise.reject (js/Error. "Migration unavailable"))))
      (-> (go-promise
            (await! (storage/ready! options))
            (storage/write! draft {:text "New draft"} options)
            (storage/remove! removed options)
            (is (true? (get (storage/drain!) account)) "Draft success means its small write is already durable")
            (await! (storage/drain-async!))
            (let [saved (await! (disk/read-local! (storage/read-disk! key)))]
              (is (= {:text "New draft"} (get-in saved [draft :value])))
              (is (not (contains? saved removed))))
            (is (some? (storage/read-disk! (storage/migration-key account))) "Failure retains the heavy backup")
            (forget! account)
            (await! (storage/ready! options))
            (is (= {:text "New draft"} (get-in @storage/*buckets [account draft :value])))
            (is (not (contains? (get @storage/*buckets account) removed)) "Backup cannot resurrect small-state removals")
            (is (= snapshot (get-in @storage/*buckets [account :public-content])))
            (await! (storage/drain-async!)))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! disk/write! write!)
                      (reset! storage/*identity identity)
                      (forget! account)
                      (.removeItem js/localStorage key)
                      (.removeItem js/localStorage (storage/migration-key account))
                      (-> (disk/remove! account) (.finally done))))))))

(deftest late-heavy-restoration-preserves-an-edit-and-a-removal
  (async done
    (let [account (random-uuid)
          identity @storage/*identity
          read! disk/read!
          *release (atom nil)
          edited [:state [:store :edited]]
          removed [:state [:store :removed]]
          snapshot {:version 1, :schema 1, :expires-at (+ (.now js/Date) 60000), :value :old}]
      (reset! storage/*identity account)
      (set! disk/read! (fn [_] (js/Promise. (fn [resolve _] (reset! *release resolve)))))
      (-> (go-promise
            (let [ready (storage/ready! {:scope :user})]
              (await! (js/Promise.resolve nil))
              (storage/write! edited :new {:scope :user})
              (storage/remove! removed {:scope :user})
              (@*release {edited snapshot, removed snapshot})
              (await! ready)
              (is (= :new (get-in @storage/*buckets [account edited :value])))
              (is (nil? (get-in @storage/*buckets [account removed])))
              (await! (storage/drain-async!))
              (set! disk/read! read!)
              (let [saved (await! (disk/read! account))]
                (is (= :new (get-in saved [edited :value])))
                (is (not (contains? saved removed))))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! disk/read! read!)
                      (reset! storage/*identity identity)
                      (forget! account)
                      (.removeItem js/localStorage (str storage/prefix (pr-str account)))
                      (-> (disk/remove! account) (.finally done))))))))
