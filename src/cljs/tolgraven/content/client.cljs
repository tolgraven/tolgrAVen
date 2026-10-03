(ns tolgraven.content.client
  (:require [ajax.core :as ajax]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [reagent.core :as r]
            [tolgraven.content.contract :as contract]
            [tolgraven.service-status :as status]))

(defonce *pending (atom {}))

(rf/reg-event-db :content/install
  (fn [db [_ bundle]]
    (-> db
        (update :content merge (contract/normalize-content (:content bundle)))
        (assoc-in [:state :content :status] :ready))))

(defn valid-bundle? [bundle ks]
  (and (= contract/version (:version bundle)) (map? (:content bundle))
       (every? #(contains? (:content bundle) %) ks)
       (every? (set contract/sections) (keys (:content bundle)))))

(declare ensure! prefetch!)

(defn request! [ks]
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
                                                    (every? #(contains? (:content @rfdb/app-db) %) (second id)))]
                                   (status/recover! id))
                                 (resolve bundle))
                             (reject (js/Error. "Invalid content response"))))
                :error-handler (fn [_] (reject (js/Error. "Content is temporarily unavailable")))}))))

(defn ensure! [requested]
  (let [ks (vec (distinct requested))
        missing (filterv #(and (not (contains? (:content @rfdb/app-db) %))
                               (not (contains? @*pending %))) ks)]
    (when (seq missing)
      (let [promise (-> (request! missing)
                        (.catch (fn [error]
                                  (status/fail! [:strapi (vec (sort missing))] "Strapi content unavailable"
                                                "Some site content could not be loaded. Previously loaded content is retained."
                                                #(prefetch! missing))
                                  (throw error)))
                        (.finally #(swap! *pending (fn [pending] (apply dissoc pending missing)))))]
        (swap! *pending into (map (fn [k] [k promise]) missing))))
  (js/Promise.all (clj->js (distinct (keep @*pending requested))))))

(defn bootstrap! []
  (try
    (let [element (.getElementById js/document "site-content-bootstrap")
          embedded (when element (js->clj (js/JSON.parse (.-textContent element)) :keywordize-keys true))]
      (when embedded
        (when-not (valid-bundle? embedded (keys (:content embedded)))
          (throw (js/Error. "Invalid embedded content")))
        (rf/dispatch-sync [:content/install embedded]))
      ;; The default loads all content. An SSR response can opt into a partial
      ;; bootstrap; module initialization/prefetch then fill only missing sections.
      (-> (ensure! (if (:deferred? embedded) (keys (:content embedded)) contract/sections))
          (.then (fn [result] (status/recover! :strapi-bootstrap) result))))
    (catch :default error
      ;; A retry fetches a fresh bundle instead of parsing the same broken snapshot.
      (when-let [element (.getElementById js/document "site-content-bootstrap")] (.remove element))
      (status/fail! :strapi-bootstrap "Strapi content could not initialize"
                    "The initial content response was invalid. Reload the page to try again."
                    #(.reload js/location))
      (js/Promise.reject error))))

(defn prefetch! [ks]
  (-> (ensure! ks) (.catch (fn [_] nil)))) ; optional; initialization can retry

(defn <prefetch> [ks]
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
