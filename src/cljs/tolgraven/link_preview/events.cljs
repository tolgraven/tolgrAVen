(ns tolgraven.link-preview.events
  (:require
    [re-frame.core :as rf]
    [tolgraven.link-preview.util :as util]))

(rf/reg-event-db
  :link-preview/register
  (fn [db [_ id candidates]]
    (assoc-in db [:state :link-preview :containers id]
              {:candidates candidates
               :count (count candidates)})))

(rf/reg-event-db
  :link-preview/unregister
  (fn [db [_ id]]
    (let [state (get-in db [:state :link-preview])
          active (:active state)]
      (assoc-in db [:state :link-preview]
                (cond-> (-> state
                            (update :containers dissoc id)
                            (update :prefetch-queue
                                    #(vec (remove (fn [candidate]
                                                   (= id (:container-id candidate)))
                                                 %))))
                  (= id (:container-id active)) (dissoc :active))))))

(rf/reg-event-db
  :link-preview/visible
  (fn [db [_ candidate]]
    (let [path [:state :link-preview]
          url (:url candidate)
          trust (:trust candidate)
          queued? (get-in db (conj path :prefetch url))]
      (if (or queued? (not (get util/prefetch-delay-ms trust)))
        db
        (-> db
            (assoc-in (conj path :prefetch url) :queued)
            (update-in (conj path :prefetch-queue) (fnil conj []) candidate))))))

(rf/reg-event-db
  :link-preview/prefetch-next
  (fn [db _]
    (update-in db [:state :link-preview :prefetch-queue]
               #(vec (rest %)))))

(rf/reg-event-db
  :link-preview/prefetched
  (fn [db [_ url]]
    (assoc-in db [:state :link-preview :prefetch url] :prefetched)))

(rf/reg-event-db
  :link-preview/open
  (fn [db [_ candidate]]
    (assoc-in db [:state :link-preview :active]
              (assoc candidate :status :preview))))

(rf/reg-event-db
  :link-preview/status
  (fn [db [_ status]]
    (assoc-in db [:state :link-preview :active :status] status)))

(rf/reg-event-db
  :link-preview/restore
  (fn [db [_ transition]]
    (assoc-in db [:state :link-preview :active]
              (assoc transition :status :returning))))

(rf/reg-event-db
  :link-preview/close
  (fn [db _]
    (update-in db [:state :link-preview] dissoc :active)))
