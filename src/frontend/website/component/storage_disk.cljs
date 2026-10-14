(ns website.component.storage-disk
  "Async heavy-cache IO. Small preferences remain in the synchronous store."
  (:require [cognitect.transit :as transit]
            [cljs.reader :as reader]
            [clojure.string :as string]
            [cljs.tools.reader.reader-types :as reader-types]))

(defonce *database (atom nil))
(def max-bytes (* 2 1024 1024))

(defn map-chunks!
  "Decode/validate independent entries within a frame budget. IO callers share
   their promise; mounted components continue reading the in-memory index."
  [f entries]
  (js/Promise.
    (fn [resolve reject]
      (letfn [(step! [remaining result]
                (try
                  (let [started (.now js/performance)]
                    (loop [remaining' remaining, result' result]
                      (if (seq remaining')
                        (let [result'' (conj result' (f (first remaining')))
                              remaining'' (next remaining')]
                          (if (and remaining'' (or (zero? (mod (count result'') 32))
                                                  (>= (- (.now js/performance) started) 6)))
                            ;; A timer after rAF lets the browser actually paint.
                            (js/requestAnimationFrame
                              #(js/setTimeout (fn [] (step! remaining'' result'')) 0))
                            (recur remaining'' result'')))
                        (resolve result'))))
                  (catch :default error (reject error))))]
        (js/setTimeout #(step! entries []) 0)))))

(defn read-local! [text]
  ;; Old envelopes and small preferences use EDN. Read their independent map
  ;; entries incrementally with the standard reader, preserving scalar types.
  (if-not text
    (js/Promise.resolve nil)
    (let [text' (string/trim text)]
      (if-not (and (<= (count text') max-bytes) (string/starts-with? text' "{")
                   (string/ends-with? text' "}"))
        (js/Promise.reject (js/Error. "Invalid preferences envelope"))
        (let [source (reader-types/string-push-back-reader (subs text' 1 (dec (count text'))))
              eof (js-obj)
              pairs! (fn pairs! []
                       (lazy-seq
                         (let [id (reader/read {:eof eof} source)]
                           (when-not (identical? id eof)
                             (let [snapshot (reader/read {:eof eof} source)]
                               (when (identical? snapshot eof) (throw (js/Error. "Invalid preferences envelope")))
                               (cons [id snapshot] (pairs!)))))))]
          (-> (map-chunks! identity (pairs!)) (.then #(into {} %))))))))

(defn- database! []
  (or @*database
      (let [pending
            (js/Promise.
              (fn [resolve reject]
                (if-not (exists? js/indexedDB)
                  (reject (js/Error. "IndexedDB is unavailable"))
                  (let [request (.open js/indexedDB "tolgraven-component-cache" 1)]
                    (set! (.-onupgradeneeded request)
                          (fn [_] (.createObjectStore (.-result request) "owners")))
                    (set! (.-onerror request) (fn [_] (reject (.-error request))))
                    (set! (.-onblocked request) (fn [_] (reject (js/Error. "Cache upgrade is blocked"))))
                    (set! (.-onsuccess request)
                          (fn [_]
                            (let [database (.-result request)]
                              (set! (.-onversionchange database)
                                    (fn [_] (.close database) (reset! *database nil)))
                              (resolve database))))))))
            retryable (.catch pending (fn [error] (reset! *database nil) (throw error)))]
        (reset! *database retryable)
        retryable)))

(defn- transaction! [account mode value]
  (-> (database!)
      (.then
        (fn [database]
          (js/Promise.
            (fn [resolve reject]
              (let [transaction (.transaction database #js ["owners"] mode)
                    store (.objectStore transaction "owners")
                    request (if (= "readwrite" mode)
                              (if (nil? value) (.delete store (pr-str account))
                                  (.put store value (pr-str account)))
                              (.get store (pr-str account)))]
                (set! (.-oncomplete transaction) (fn [_] (resolve (.-result request))))
                (set! (.-onabort transaction) (fn [_] (reject (or (.-error transaction)
                                                                (js/Error. "Cache transaction aborted")))))
                (set! (.-onerror transaction) (fn [_] (reject (.-error transaction)))))))))))

(defn remove! [account] (transaction! account "readwrite" nil))

(defn read! [account]
  (-> (transaction! account "readonly" nil)
      (.then (fn [record]
               (if-not record
                 nil
                 (let [entries (.-entries record)]
                   (when-not (and (= 1 (.-version record)) (js/Array.isArray entries)
                                  (<= (.-length entries) 10000)
                                  (every? #(and (js/Array.isArray %) (= 2 (.-length %))
                                                (every? string? (array-seq %))) (array-seq entries))
                                  (<= (reduce + 0 (map #(reduce + 0 (map count (array-seq %)))
                                                      (array-seq entries))) max-bytes))
                     (throw (js/Error. "Invalid cache envelope")))
                   (-> (map-chunks! (fn [entry]
                                     [(transit/read (transit/reader :json) (aget entry 0))
                                      (transit/read (transit/reader :json) (aget entry 1))])
                                   (array-seq entries))
                       (.then #(into {} %)))))))))

(defn write! [account bucket]
  (-> (map-chunks! (fn [[id snapshot]]
                    #js [(transit/write (transit/writer :json) id)
                         (transit/write (transit/writer :json) snapshot)]) bucket)
      (.then (fn [entries]
               (when (> (reduce + 0 (map #(reduce + 0 (map count (array-seq %))) entries)) max-bytes)
                 (throw (js/Error. "Cache envelope is too large")))
               (transaction! account "readwrite" #js {:version 1, :entries (into-array entries)})))))
