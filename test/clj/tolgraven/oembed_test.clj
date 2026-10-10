(ns tolgraven.oembed-test
  (:require [clj-http.client :as http]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [tolgraven.oembed :as oembed]
            [tolgraven.components.oembed.contract :as contract]))

(deftest only-recognized-player-endpoints-retain-their-provider-origin
  (doseq [source ["https://w.soundcloud.com/player/?url=https%3A%2F%2Fapi.soundcloud.com%2Ftracks%2F123"
                  "https://www.youtube-nocookie.com/embed/ABCDEFGHIJK"
                  "https://player.vimeo.com/video/12345"]]
    (is (= source (contract/player-url source))))
  (doseq [source ["http://w.soundcloud.com/player/" "https://w.soundcloud.com.attacker.example/player/"
                  "https://w.soundcloud.com@attacker.example/player/"
                  "https://user@w.soundcloud.com/player/" "https://w.soundcloud.com:8443/player/"
                  "https://w.soundcloud.com/redirect" "https://tolgraven.se/"
                  "javascript:alert(1)" "//w.soundcloud.com/player/" nil]]
    (is (nil? (contract/player-url source)))))

(deftest native-player-metadata-is-derived-from-markup-not-supplied-by-the-provider
  (let [source "https://w.soundcloud.com/player/?url=test"
        result (oembed/normalize-result {:html (str "<iframe src='" source "'></iframe><script>attack()</script>")
                                         :player-src "https://attacker.example/"
                                         :height 400})]
    (is (= source (:player-src result)))
    (is (m/validate contract/result result)))
  (let [result (oembed/normalize-result {:html "<iframe src='https://attacker.example/'></iframe>"
                                         :player-src "https://w.soundcloud.com/player/"
                                         :height "400"})]
    (is (not (contains? result :player-src)))
    (is (= 166 (:height result)))
    (is (m/validate contract/result result))))

(deftest concurrent-player-requests-share-one-read-and-subsequent-cache-hits
  (let [cache @#'oembed/*cache
        saved @cache
        started (promise)
        release (promise)
        calls (atom 0)
        url "https://soundcloud.com/cache/deduplication"]
    (reset! cache {})
    (try
      (with-redefs [http/get (fn [_ _]
                              (swap! calls inc)
                              (deliver started true)
                              @release
                              {:body {:html "<p>Player</p>"
                                      :height 166}})]
        (let [first-read (future (oembed/response! url))]
          (is (= true (deref started 3000 nil)))
          (let [second-read (future (oembed/response! url))]
            (deliver release true)
            (is (= 200 (:status (deref first-read 3000 nil))))
            (is (= @first-read (deref second-read 3000 nil)))
            (is (= @first-read (oembed/response! url)))
            (is (= 1 @calls)))))
      (finally (deliver release true) (reset! cache saved)))))

(deftest cache-is-bounded-and-failed-reads-expire-sooner
  (let [cache @#'oembed/*cache
        saved @cache
        calls (atom 0)]
    (reset! cache {})
    (try
      (with-redefs [http/get (fn [_ _]
                              (swap! calls inc)
                              {:body {:html "<p>Player</p>"}})]
        (doseq [index (range (+ 2 oembed/cache-limit))]
          (oembed/response! (str "https://soundcloud.com/cache/" index)))
        (is (= oembed/cache-limit (count @cache))))
      (let [url "https://soundcloud.com/cache/failure"]
        (with-redefs [http/get (fn [_ _] (throw (Exception. "Provider unavailable")))]
          (is (= 503 (:status (oembed/response! url))))
          (is (<= (- (get-in @cache [url :expires-at]) (System/currentTimeMillis))
                  oembed/failure-ttl-ms)))
        (swap! cache assoc-in [url :expires-at] 0)
        (with-redefs [http/get (fn [_ _] {:body {:html "<p>Recovered</p>"}})]
          (is (= 200 (:status (oembed/response! url))))))
      (finally (reset! cache saved)))))

(deftest blank-provider-titles-use-an-accessible-fallback
  (doseq [title [nil "" " \t\n" 42]]
    (is (= "Embedded player" (:title (oembed/normalize-result {:title title})))))
  (is (= "A player" (:title (oembed/normalize-result {:title "A player"})))))

(deftest cache-pressure-does-not-start-another-read-of-a-pending-url
  (let [cache @#'oembed/*cache
        saved @cache
        started (promise)
        release (promise)
        calls (atom {})
        pending-url "https://soundcloud.com/cache/pending"]
    (reset! cache {})
    (try
      (with-redefs [oembed/cache-limit 2
                    http/get (fn [url _]
                               (swap! calls update url (fnil inc 0))
                               (when (.contains ^String url "pending")
                                 (deliver started true)
                                 @release)
                               {:body {:html "<p>Player</p>"}})]
        (let [first-read (future (oembed/response! pending-url))]
          (try
            (is (= true (deref started 3000 nil)))
            (let [entry (get @cache pending-url)]
              (oembed/response! "https://soundcloud.com/cache/completed")
              (oembed/response! "https://soundcloud.com/cache/replacement")
              (is (identical? (:value entry) (get-in @cache [pending-url :value])))
              (is (= 2 (count @cache))))
            (deliver release true)
            (is (= 200 (:status (deref first-read 3000 nil))))
            (is (= @first-read (oembed/response! pending-url)))
            (is (= 1 (get @calls "https://noembed.com/embed?url=https%3A%2F%2Fsoundcloud.com%2Fcache%2Fpending")))
            (finally (deliver release true) (deref first-read 3000 nil)))))
      (finally (deliver release true) (reset! cache saved)))))
