(ns tolgraven.content.client
  (:require
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.component.registry]
    [ajax.core :as ajax]
    [clojure.string :as string]
    [tolgraven.react :as rf]
    [reagent.core :as r]
    [tolgraven.content.contract :as contract]
    [tolgraven.validation :as validation]
    [tolgraven.service-status :as status]
    [tolgraven.component.storage :as storage]
    [tolgraven.component.restore :as restore]))

(def cache-options {:scope :public :version contract/version :ttl-ms 1800000})

(defonce *pending (atom {}))
(defonce *queued (atom {}))
(defonce *tick (atom nil))

(rf/reg-sub :content/cache
  (fn [db _] (:content db)))
(defn- cached-content [] @(rf/sub [:content/cache]))

(rf/reg-event-db :content/install
  (fn [db [_ bundle]]
    (-> db
        (update :content merge (contract/normalize-content (:content bundle)))
        (assoc-in [:state :content :status] :ready))))

(defn valid-bundle? [bundle ks]
  (try (contract/checked-bundle bundle ks) true
       (catch :default _ false)))

(declare ensure! prefetch!)

(defn- request-ready! [ks]
  (js/Promise.
   (fn [resolve reject]
     (ajax/GET (if (= (set ks) (set contract/sections)) "/api/content/bootstrap"
                  (str "/api/content?keys=" (js/encodeURIComponent (string/join "," (map name ks)))))
               {:timeout 15000 :response-format (ajax/json-response-format {:keywords? true})
                :handler (fn [bundle]
                           (if (valid-bundle? bundle ks)
                             (do (rf/dispatch-sync [:content/install bundle])
                                 (doseq [id (keys @status/*failures)
                                         :when (and (vector? id) (= :strapi (first id))
                                                    (every? #(contains? (cached-content) %) (second id)))]
                                   (status/recover! id))
                                 (resolve bundle))
                             (reject (js/Error. "Invalid content response"))))
                :error-handler (fn [_] (reject (js/Error. "Content is temporarily unavailable")))}))))

(defn request! [ks]
  (-> (validation/when-ready!)
      (.then (fn [_] (request-ready! ks)))))

(defn drain!
  "One network request for all sections requested during this tick."
  []
  (reset! *tick nil)
  (let [batch @*queued ks (vec (sort (keys batch)))]
    (reset! *queued {})
    (when (seq ks)
      (-> (js/Promise.resolve nil)
          (.then (fn [_] (request! ks)))
          (.then (fn [bundle]
                   (swap! *pending #(apply dissoc % ks))
                   (storage/schedule!)
                   (doseq [[_ {:keys [resolve]}] batch] (resolve bundle)))
                 (fn [error]
                   (swap! *pending #(apply dissoc % ks))
                   (status/fail! [:strapi ks] "Strapi content unavailable"
                                 "Some site content could not be loaded. Previously loaded content is retained."
                                 #(prefetch! ks))
                   (doseq [[_ {:keys [reject]}] batch] (reject error))))))))

(defn ensure! [requested]
  (doseq [k (distinct requested)
          :when (and (not (contains? (cached-content) k))
                     (not (contains? @*pending k)))]
    (let [promise (js/Promise. (fn [resolve reject]
                                (swap! *queued assoc k {:resolve resolve :reject reject})))]
      (swap! *pending assoc k promise)))
  (when (and (seq @*queued) (nil? @*tick))
    (reset! *tick (js/setTimeout drain! 0)))
  (js/Promise.all (into-array (distinct (keep @*pending requested)))))

(rf/reg-fx :content/load #(prefetch! %))
(rf/reg-event-fx :content/load (fn [_ [_ ks]] {:content/load ks}))

(defn- bootstrap-ready! []
  (try
    (let [element (.getElementById js/document "site-content-bootstrap")
          embedded (when element (js->clj (js/JSON.parse (.-textContent element)) :keywordize-keys true))]
      (when embedded
        (when-not (valid-bundle? embedded (keys (:content embedded)))
          (throw (js/Error. "Invalid embedded content")))
        (rf/dispatch-sync [:content/install embedded]))
      ;; Server content wins; fill only missing sections on an external back.
      (when (:back? @restore/*context)
        (when-let [saved (storage/read! :public-content cache-options)]
          (when (valid-bundle? (:value saved) (keys (:content (:value saved))))
            (rf/dispatch-sync [:content/install
                               (update (:value saved) :content
                                       #(apply dissoc % (keys (cached-content))))]))))
      (storage/track! :public-content
                      #(hash-map :version contract/version :content (cached-content)) cache-options)
      ;; The default loads all content. An SSR response can opt into a partial
      ;; bootstrap; module initialization/prefetch then fill only missing sections.
      (-> (ensure! (cond
                     (:deferred? embedded) (keys (:content embedded))
                     (:back? @restore/*context)
                     (let [route (or (second (string/split (.-pathname js/location) #"/")) "home")]
                       (contract/keys-for-route (if (#{"" "about" "services" "hire"} route) :home (keyword route))))
                     :else contract/sections))
          (.then (fn [result] (status/recover! :strapi-bootstrap) (storage/schedule!) result))))
    (catch :default error
      ;; A retry fetches a fresh bundle instead of parsing the same broken snapshot.
      (when-let [element (.getElementById js/document "site-content-bootstrap")] (.remove element))
      (status/fail! :strapi-bootstrap "Strapi content could not initialize"
                    "The initial content response was invalid. Reload the page to try again."
                    #(.reload js/location))
      (js/Promise.reject error))))

(defn bootstrap-server!
  "Install content supplied with the validated network SSR pair.
   Browser disk/transport responses still pass through their own validation."
  [snapshot]
  (rf/dispatch-sync [:content/install
                     {:version contract/version :content (:content snapshot)}])
  (storage/track! :public-content
                  #(hash-map :version contract/version :content (cached-content)) cache-options)
  (-> (ensure! (keys (:content snapshot)))
      (.then (fn [result] (status/recover! :strapi-bootstrap) result))))

(defn bootstrap! []
  (-> (storage/ready!) (.then (fn [_] (bootstrap-ready!)))))

(defn prefetch! [ks]
  (-> (ensure! ks) (.catch (fn [_] nil)))) ; optional; initialization can retry

(defc <prefetch> [ks]
  (r/with-let [*observer (atom nil)
               attach! (fn [element]
                         (when-let [observer @*observer] (.disconnect observer))
                         (reset! *observer nil)
                         (when element
                           (if (exists? js/IntersectionObserver)
                             (let [observer (js/IntersectionObserver.
                                             (fn [entries observer]
                                               (when (some #(.-isIntersecting %) (array-seq entries))
                                                 (.disconnect observer)
                                                 (prefetch! ks)))
                                             #js {:rootMargin "800px 0px" :threshold 0})]
                               (reset! *observer observer)
                               (.observe observer element))
                             (prefetch! ks))))]
    [:div {:aria-hidden true :style {:height "1px" :margin-bottom "-1px" :pointer-events "none"}
           :ref attach!}]
    (finally (when-let [observer @*observer] (.disconnect observer)))))
