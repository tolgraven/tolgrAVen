(ns website.content.client
  (:require
    [website.async :as a]
    [website.macros :refer-macros [defc]]
    [website.component.registry]
    [website.component.visibility :as visibility]
    [website.http :as http]
    [clojure.string :as string]
    [website.react :as r]

    [website.content.contract :as contract]
    [website.validation :as validation]
    [website.service-status :as status]
    [website.component.storage :as storage]
    [website.component.restore :as restore]))

(def request-timeout-ms 15000)
;; Two bounded transport attempts plus the shared queue/settlement margin.
(def acquisition-timeout-ms (+ (* 2 request-timeout-ms) 1000))

(def cache-options {:scope :public :version contract/version :ttl-ms 1800000})

(defonce *pending (atom {}))
(defonce *queued (atom {}))
(defonce *tick (atom nil))

(r/reg-sub :content/cache
  (fn [db _] (:content db)))
(defn- cached-content [] @(r/sub [:content/cache]))

(r/reg-event-db :content/install
  (fn [db [_ bundle]]
    (-> db
        (update :content merge (contract/normalize-content (:content bundle)))
        (assoc-in [:state :content :status] :ready))))

(defn valid-bundle? [bundle ks]
  (try (contract/checked-bundle bundle ks) true
       (catch :default _ false)))

(declare ensure! prefetch!)

(defn- recover-sections! []
  (doseq [id (keys @status/*failures)
          :when (and (vector? id) (= :strapi (first id))
                     (every? #(contains? (cached-content) %) (second id)))]
    (status/recover! id)))

(defn- ^:async request-ready! [ks]
  (let [all? (= (set ks) (set contract/sections))
        request (cond-> {:uri (if all? "/api/content/bootstrap" "/api/content") :timeout request-timeout-ms}
                  (not all?) (assoc :url-params {:keys (string/join "," (map name ks))}))
        bundle (try (await (http/request! request))
                    (catch :default _ (throw (js/Error. "Content is temporarily unavailable"))))]
    (when-not (valid-bundle? bundle ks) (throw (js/Error. "Invalid content response")))
    (r/dispatch-sync [:content/install bundle])
    (recover-sections!)
    bundle))

(defn request! [ks]
  (-> (validation/when-ready!)
      (a/then (fn [_] (request-ready! ks)))))

(defn- complete-batch! [batch ks bundle]
  (swap! *pending #(apply dissoc % ks))
  (storage/schedule!)
  (doseq [{resolve! :resolve} (vals batch)] (resolve! bundle)))

(defn- fail-section! [batch ks error]
  (swap! *pending #(apply dissoc % ks))
  (status/fail! [:strapi ks] "Strapi content unavailable"
                "Some site content could not be loaded. Previously loaded content is retained."
                #(prefetch! ks))
  (doseq [{reject! :reject} (vals batch)] (reject! error)))

(defn- acquire-batch! [batch]
  (let [ks (vec (sort (keys batch)))
        ;; Retry separately so a bad section cannot fail unrelated module loads.
        recover! (fn [error]
                   (if (next ks)
                     (a/all (map #(acquire-batch! (select-keys batch [%])) ks))
                     (fail-section! batch ks error)))]
    (-> (a/resolved nil)
        (a/then (fn [_] (request! ks)))
        (a/then #(complete-batch! batch ks %) recover!))))

(defn drain!
  "Batch this tick's requests; isolate sections if the shared request fails."
  []
  (reset! *tick nil)
  (let [batch @*queued]
    (reset! *queued {})
    (when (seq batch) (acquire-batch! batch))))

(defn ensure! [requested]
  (doseq [k (distinct requested)
          :when (and (not (contains? (cached-content) k))
                     (not (contains? @*pending k)))]
    (let [{:keys [promise resolve! reject!]} (a/deferred)]
      ;; The queue survives hot reload between enqueue and the next drain.
      (swap! *queued assoc k {:resolve resolve! :reject reject!})
      (swap! *pending assoc k promise)))
  (when (and (seq @*queued) (nil? @*tick))
    (reset! *tick (js/setTimeout drain! 0)))
  (a/all (distinct (keep @*pending requested))))

(r/reg-fx :content/load #(prefetch! %))
(r/reg-event-fx :content/load (fn [_ [_ ks]] {:content/load ks}))

(defn bootstrap-keys [embedded _pathname]
  (if (:deferred? embedded)
    (vec (keys (:content embedded)))
    contract/shell-content))

(defn- bootstrap-ready! []
  (try
    (let [element (.getElementById js/document "site-content-bootstrap")
          embedded (when element (js->clj (js/JSON.parse (.-textContent element)) :keywordize-keys true))]
      (when embedded
        (when-not (valid-bundle? embedded (keys (:content embedded)))
          (throw (js/Error. "Invalid embedded content")))
        (r/dispatch-sync [:content/install embedded]))
      ;; Server content wins; fill only missing sections on an external back.
      (when (:back? @restore/*context)
        (when-let [saved (storage/read! :public-content cache-options)]
          (when (valid-bundle? (:value saved) (keys (:content (:value saved))))
            (r/dispatch-sync [:content/install
                               (update (:value saved) :content
                                       #(apply dissoc % (keys (cached-content))))]))))
      (storage/track! :public-content
                      #(hash-map :version contract/version :content (cached-content)) cache-options)
      ;; Startup needs only the shared shell. Route/feature modules acquire
      ;; their own content through the loader and retain their local retry UI.
      (-> (ensure! (bootstrap-keys embedded (.-pathname js/location)))
          (a/then (fn [result] (status/recover! :strapi-bootstrap) (storage/schedule!) result))))
    (catch :default error
      ;; A retry fetches a fresh bundle instead of parsing the same broken snapshot.
      (when-let [element (.getElementById js/document "site-content-bootstrap")] (.remove element))
      (status/fail! :strapi-bootstrap "Strapi content could not initialize"
                    "The initial content response was invalid. Reload the page to try again."
                    #(.reload js/location))
      (a/rejected error))))

(defn bootstrap-server!
  "Install content supplied with the validated network SSR pair.
   Browser disk/transport responses still pass through their own validation."
  [snapshot]
  (r/dispatch-sync [:content/install
                     {:version contract/version :content (:content snapshot)}])
  (storage/track! :public-content
                  #(hash-map :version contract/version :content (cached-content)) cache-options)
  (-> (ensure! (keys (:content snapshot)))
      (a/then (fn [result] (status/recover! :strapi-bootstrap) result))))

(defn bootstrap! []
  (-> (storage/ready!) (a/then (fn [_] (bootstrap-ready!)))))

(defn prefetch! [ks]
  (-> (ensure! ks) (a/catch-error (fn [_] nil)))) ; optional; initialization can retry

(defc <prefetch> [ks]
  [visibility/<sentinel> {:event [:content/load ks]
                        :once? true
                        :threshold 0
                        :root-margin "800px 0px"}])
