(ns tolgraven.link-preview.subs
  (:require
    [re-frame.core :as rf]
    [tolgraven.link-preview.util :as util]))

(rf/reg-sub
  :link-preview/raw-text
  (fn [_ [_ text]]
    (or text "")))

(rf/reg-sub
  :link-preview/candidates
  (fn [[_ text]]
    [(rf/subscribe [:link-preview/raw-text text])])
  (fn [[text] [_ _ base-url]]
    (util/external-urls text base-url)))

(rf/reg-sub
  :link-preview/state
  (fn [db _]
    (get-in db [:state :link-preview]
            {:containers {}
             :prefetch-queue []})))

(rf/reg-sub
  :link-preview/containers
  :<- [:link-preview/state]
  (fn [state _]
    (:containers state)))

(rf/reg-sub
  :link-preview/link-count
  :<- [:link-preview/containers]
  (fn [containers _]
    (reduce + (map :count (vals containers)))))
