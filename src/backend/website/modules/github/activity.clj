(ns website.modules.github.activity
  "Read GitHub's public calendar from a fixed origin with bounded time and cache."
  (:require [clojure.string :as string]
            [website.modules.github.provider :as provider]
            [website.components.activity-schema :as schema]
            [website.validation :as validation]
            [website.cache.inflight :as inflight])
  (:import [org.jsoup Jsoup]
           [java.util.concurrent Semaphore]))

(def user-schema [:re #"^[A-Za-z0-9][A-Za-z0-9-]{0,38}$"])
(defonce ^:private *cache (atom {}))
(defonce ^:private slots (Semaphore. 4))

(defn parse-calendar [html]
  (let [document (Jsoup/parse html)
        labels (into {} (for [tip (.select document "tool-tip[for]")]
                          [(.attr tip "for") (.text tip)]))
        days (->> (.select document "td[data-date][data-level]")
                  (mapv (fn [cell]
                          (let [label (get labels (.id cell))
                                count (cond
                                        (string/starts-with? (or label "") "No contributions") 0
                                        :else (some-> (re-find #"^([0-9,]+) contribution" (or label ""))
                                                      second (string/replace "," "") Long/parseLong))]
                            (when (nil? count) (throw (ex-info "Calendar count unavailable" {})))
                            {:date (.attr cell "data-date")
                             :count count
                             :level (Long/parseLong (.attr cell "data-level"))})))
                  (sort-by :date)
                  vec)]
    (when-not (and (<= 1 (count days) 371)
                   (every? #(and (re-matches #"\d{4}-\d{2}-\d{2}" (:date %))
                                 (<= 0 (:level %) 4)) days))
      (throw (ex-info "Invalid public calendar" {})))
    {:days days}))

(defn fetch-calendar! [user]
  (-> (Jsoup/connect (str "https://github.com/users/" user "/contributions"))
      (.timeout 7000)
      (.maxBodySize 1048576)
      (.followRedirects false)
      (.get)
      (.outerHtml)
      parse-calendar))

(defn- unavailable []
  {:status 503
   :headers {"Cache-Control" "no-store"}
   :body {:error "GitHub activity is temporarily unavailable"}})

(defn response! [user]
  (if-not (and (string? user) (re-matches #"[A-Za-z0-9][A-Za-z0-9-]{0,38}" user))
    {:status 400, :body {:error "Invalid GitHub user"}}
    (let [now (System/currentTimeMillis)
          [owner? entry] (inflight/acquire! *cache user now 900000 128)]
      (when owner?
        (let [response (if (.tryAcquire slots)
                         (try {:status 200
                               :headers {"Cache-Control" "public, max-age=300"}
                               :body (fetch-calendar! user)}
                              (catch Exception _ (unavailable))
                              (finally (.release slots)))
                         (unavailable))]
          (when-not (= 200 (:status response))
            (inflight/shorten! *cache user entry (+ now 60000)))
          (deliver (:value entry) response)))
      (if entry (deref (:value entry) 8000 (unavailable)) (unavailable)))))

(defonce ^:private *day-cache (atom {}))

(defn valid-date? [date]
  (try
    (and (string? date) (re-matches #"\d{4}-\d{2}-\d{2}" date)
         (not (.isAfter (java.time.LocalDate/parse date) (java.time.LocalDate/now java.time.ZoneOffset/UTC))))
    (catch Exception _ false)))

(def ^:private day-headings
  [[:commits #"^Created ([0-9,]+) commits? in "]
   [:issues #"^(?:Opened|Created) ([0-9,]+|an?) (?:other )?issues?(?: in |$)"]
   [:pull-requests #"^(?:Opened|Created) ([0-9,]+|a) (?:other )?pull requests?(?: in |$)"]
   [:reviews #"^Reviewed ([0-9,]+|a) pull requests?(?: in |$)"]
   [:repositories #"^Created ([0-9,]+|a) repositor(?:y|ies)(?: |$)"]
   [:restricted #"^([0-9,]+) contributions? in private repositor(?:y|ies)(?: |$)"]])

(defn parse-day
  "Read only public timeline category headings; verify against the calendar total.
   Featured items and 'other' rollups are disjoint. Never count nested repo lists."
  [html date]
  (let [document (Jsoup/parse html)
        day (first (filter #(= date (:date %)) (:days (parse-calendar html))))
        timeline (.selectFirst document ".contribution-activity-listing")
        range (.selectFirst document "form.js-show-more-timeline-form[data-from][data-to]")
        types (reduce
                (fn [counts item]
                  (let [heading (some-> (.selectFirst item "summary, h4, span.f4") .text)
                        [kind amount] (some (fn [[kind pattern]]
                                              (when-let [[_ amount] (re-find pattern (or heading ""))]
                                                [kind (if (#{"a" "an"} amount) 1
                                                          (Long/parseLong (string/replace amount "," "")))]))
                                            day-headings)]
                    (when-not kind (throw (ex-info "Public activity heading unavailable" {})))
                    (update counts kind + amount)))
                (zipmap (map first day-headings) (repeat 0))
                (.select document ".contribution-activity-listing .TimelineItem-body"))
        result {:date date, :types types}]
    (when-not (and day timeline range
                   (= date (.attr range "data-from") (.attr range "data-to"))
                   (= (:count day) (reduce + (vals types))))
      (throw (ex-info "Public activity breakdown unavailable" {})))
    (validation/check! "GitHub day detail" schema/breakdown result)))

(defn fetch-day! [user date]
  (when-not (and (provider/user? user) (valid-date? date))
    (throw (ex-info "Invalid GitHub activity day" {:status 400})))
  (-> (Jsoup/connect (str "https://github.com/" user
                         "?action=show&controller=profiles&tab=contributions&user_id=" user
                         "&from=" date "&to=" date))
      (.header "Accept-Language" "en")
      (.timeout 7000)
      (.maxBodySize 1048576)
      (.followRedirects false)
      (.get)
      (.outerHtml)
      (parse-day date)))

(defn day-response! [user date]
  (if-not (and (provider/user? user) (valid-date? date))
    {:status 400, :body {:error "Invalid GitHub activity day"}}
    (let [now (System/currentTimeMillis)
          key [user date]
          [owner? entry] (inflight/acquire! *day-cache key now 900000 512)]
      (when owner?
        (let [response (if (.tryAcquire slots)
                         (try {:status 200
                               :headers {"Cache-Control" "public, max-age=300"}
                               :body (fetch-day! user date)}
                              (catch Exception _ (unavailable))
                              (finally (.release slots)))
                         (unavailable))]
          (when-not (= 200 (:status response))
            (inflight/shorten! *day-cache key entry (+ now 60000)))
          (deliver (:value entry) response)))
      (if entry (deref (:value entry) 12000 (unavailable)) (unavailable)))))
