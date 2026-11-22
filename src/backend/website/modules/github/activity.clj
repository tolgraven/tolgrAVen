(ns website.modules.github.activity
  "Read GitHub's public calendar from a fixed origin with bounded time and cache."
  (:require [clojure.string :as string]
            [website.modules.github.provider :as provider]
            [website.components.activity-schema :as schema]
            [website.validation :as validation]
            [website.cache.inflight :as inflight])
  (:import [org.jsoup Jsoup]
           [org.jsoup.nodes Document Element]
           [java.util.concurrent Semaphore]))

(def user-schema [:re #"^[A-Za-z0-9][A-Za-z0-9-]{0,38}$"])
(defonce ^:private *cache (atom {}))
(defonce ^:private slots (Semaphore. 4))

(defn- contribution-number [text]
  (or (parse-long (string/replace text "," ""))
      (throw (ex-info "Invalid public activity count" {}))))

(defn- calendar-labels [^Document document]
  (into {} (for [^Element tip (.select document "tool-tip[for]")]
             [(.attr tip "for") (.text tip)])))

(defn- calendar-entry [labels ^Element cell]
  (let [label (get labels (.id cell) "")
        [_ amount] (re-find #"^([0-9,]+) contribution" label)
        date (.attr cell "data-date")
        level (parse-long (.attr cell "data-level"))]
    (when-not (and (re-matches #"\d{4}-\d{2}-\d{2}" date) level (<= 0 level 4))
      (throw (ex-info "Invalid public calendar" {})))
    {:date date
     :level level
     :count (cond
              amount (contribution-number amount)
              (string/starts-with? label "No contributions") 0
              :else (throw (ex-info "Calendar count unavailable" {})))}))

(defn- calendar-days [^Document document]
  (let [labels (calendar-labels document)
        cells (.select document "td[data-date][data-level]")]
    (when-not (<= 1 (count cells) 371)
      (throw (ex-info "Invalid public calendar" {})))
    (->> cells (map #(calendar-entry labels %)) (sort-by :date) vec)))

(defn parse-calendar [html]
  {:days (calendar-days (Jsoup/parse html))})

(defn fetch-calendar! [user]
  (let [document (-> (Jsoup/connect (str "https://github.com/users/" user "/contributions"))
                     (.timeout 7000)
                     (.maxBodySize 1048576)
                     (.followRedirects false)
                     (.get))]
    {:days (calendar-days document)}))

(defn- unavailable []
  {:status 503
   :headers {"Cache-Control" "no-store"}
   :body {:error "GitHub activity is temporarily unavailable"}})

(defn- load-response! [load!]
  (if (.tryAcquire slots)
    (try
      {:status 200 :headers {"Cache-Control" "public, max-age=300"} :body (load!)}
      (catch Exception _ (unavailable))
      (finally (.release slots)))
    (unavailable)))

(defn- cached-response! [cache key limit wait-ms load!]
  (let [now (System/currentTimeMillis)
        [owner? entry] (inflight/acquire! cache key now 900000 limit)]
    (when owner?
      (let [response (load-response! load!)]
        (when-not (= 200 (:status response))
          (inflight/shorten! cache key entry (+ now 60000)))
        (deliver (:value entry) response)))
    (if entry (deref (:value entry) wait-ms (unavailable)) (unavailable))))

(defn response! [user]
  (if (provider/user? user)
    (cached-response! *cache user 128 8000 #(fetch-calendar! user))
    {:status 400 :body {:error "Invalid GitHub user"}}))

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

(defn- count-heading [totals ^Element item]
  (let [heading (some-> (.selectFirst item "summary, h4, span.f4") .text)
        match (fn [[category pattern]]
                (when-let [[_ amount] (re-find pattern (or heading ""))]
                  [category (if (#{"a" "an"} amount) 1 (contribution-number amount))]))]
    (if-let [[category amount] (some match day-headings)]
      (update totals category + amount)
      (throw (ex-info "Public activity heading unavailable" {})))))

(defn- day-breakdown [^Document document date]
  (let [day (some #(when (= date (:date %)) %) (calendar-days document))
        timeline (.selectFirst document ".contribution-activity-listing")
        period (.selectFirst document "form.js-show-more-timeline-form[data-from][data-to]")
        empty-totals (zipmap (map first day-headings) (repeat 0))
        totals (reduce count-heading empty-totals (.select document ".contribution-activity-listing .TimelineItem-body"))]
    (when-not (and day timeline period
                   (= date (.attr period "data-from") (.attr period "data-to"))
                   (= (:count day) (reduce + (vals totals))))
      (throw (ex-info "Public activity breakdown unavailable" {})))
    (validation/check! "GitHub day detail" schema/breakdown {:date date :types totals})))

(defn parse-day
  "Read the public timeline category headings and verify their sum against the
   calendar total. Featured items and 'other' rollups are disjoint."
  [html date]
  (day-breakdown (Jsoup/parse html) date))

(defn fetch-day! [user date]
  (when-not (and (provider/user? user) (valid-date? date))
    (throw (ex-info "Invalid GitHub activity day" {:status 400})))
  (-> (Jsoup/connect (str "https://github.com/" user
                         "?action=show&controller=profiles&tab=contributions&user_id=" user
                         "&from=" date "&to=" date))
      ;; GitHub's public include-fragment URL returns the populated timeline
      ;; only for an HTML fragment request; an ordinary navigation stays lazy.
      (.header "Accept" "text/html")
      (.header "X-Requested-With" "XMLHttpRequest")
      (.header "Accept-Language" "en")
      (.timeout 7000)
      (.maxBodySize 1048576)
      (.followRedirects false)
      (.get)
      (day-breakdown date)))

(defn day-response! [user date]
  (if (and (provider/user? user) (valid-date? date))
    (cached-response! *day-cache [user date] 512 12000 #(fetch-day! user date))
    {:status 400 :body {:error "Invalid GitHub activity day"}}))
