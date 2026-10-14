(ns website.modules.link-preview.events
  (:require
    [website.react :as r]
    [website.modules.link-preview.util :as util]))

(r/reg-event-db
  :link-preview/register
  (fn [db [_ id candidates]]
    (assoc-in db [:state :link-preview :containers id]
              {:candidates candidates
               :count (count candidates)})))

(r/reg-event-db
  :link-preview/unregister
  (fn [db [_ id]]
    (let [state (get-in db [:state :link-preview])
          active (:active state)
          removed-urls (into #{} (comp (filter #(= id (:container-id %)))
                                      (map :url))
                             (:prefetch-queue state))]
      (assoc-in db [:state :link-preview]
                (cond-> (-> state
                            (update :containers dissoc id)
                            (update :prefetch #(apply dissoc % removed-urls))
                            (update :prefetch-queue
                                    #(vec (remove (fn [candidate]
                                                   (= id (:container-id candidate)))
                                                 %))))
                  (= id (:container-id active)) (dissoc :active))))))

(r/reg-event-db
  :link-preview/visible
  (fn [db [_ candidate]]
    (let [path [:state :link-preview]
          url (:url candidate)
          trust (:trust candidate)
          queued? (get-in db (conj path :prefetch url))]
      (if (or queued? (contains? (get-in db (conj path :visited) #{}) url)
              (not (get util/prefetch-delay-ms trust)))
        db
        (-> db
            (assoc-in (conj path :prefetch url) :queued)
            (update-in (conj path :prefetch-queue) (fnil conj []) candidate))))))

(r/reg-event-db
  :link-preview/prefetch-next
  (fn [db _]
    (update-in db [:state :link-preview :prefetch-queue]
               #(vec (rest %)))))

(r/reg-event-db
  :link-preview/prefetched
  (fn [db [_ url]]
    (assoc-in db [:state :link-preview :prefetch url] :prefetched)))

(r/reg-event-db
  :link-preview/open
  (fn [db [_ candidate]]
    (if (contains? (get-in db [:state :link-preview :visited] #{}) (:url candidate))
      db
      (assoc-in db [:state :link-preview :active]
                (assoc candidate :status :preview)))))

(r/reg-event-db
  :link-preview/status
  (fn [db [_ status]]
    (if (get-in db [:state :link-preview :active])
      (assoc-in db [:state :link-preview :active :status] status)
      db)))

(r/reg-event-db
  :link-preview/visited
  {:args [:tuple :string]}
  (fn [db [_ url]]
    (update-in db [:state :link-preview]
               #(-> % (update :visited (fnil conj #{}) url) (dissoc :active)))))

(r/reg-event-db
  :link-preview/close
  (fn [db _]
    (update-in db [:state :link-preview] dissoc :active)))
