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

(def day-query
  "query($user:String!,$from:DateTime!,$to:DateTime!){user(login:$user){contributionsCollection(from:$from,to:$to){totalCommitContributions totalIssueContributions totalPullRequestContributions totalPullRequestReviewContributions totalRepositoryContributions restrictedContributionsCount}}}")

(defn fetch-day! [user date]
  (when-not (seq (provider/token)) (throw (ex-info "Contribution detail token unavailable" {})))
  (let [value (provider/request! :post "/graphql"
                {:content-type :json
                 :form-params {:query day-query
                               :variables {:user user
                                           :from (str date "T00:00:00Z")
                                           :to (str date "T23:59:59Z")}}})
        collection (get-in value [:data :user :contributionsCollection])
        result {:date date
                :types (into {} (map (fn [[kind field]] [kind (get collection field)]))
                             {:commits :totalCommitContributions
                              :issues :totalIssueContributions
                              :pull-requests :totalPullRequestContributions
                              :reviews :totalPullRequestReviewContributions
                              :repositories :totalRepositoryContributions
                              :restricted :restrictedContributionsCount})}]
    (validation/check! "GitHub day detail" schema/breakdown result)
    result))

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
