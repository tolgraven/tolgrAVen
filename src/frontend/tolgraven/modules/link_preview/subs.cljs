(ns tolgraven.modules.link-preview.subs
  (:require
    [tolgraven.react :as r]
    [tolgraven.modules.link-preview.util :as util]))

(r/reg-sub
  :link-preview/raw-text
  (fn [_ [_ text]]
    (or text "")))

(r/reg-sub
  :link-preview/candidates
  (fn [[_ text]]
    [(r/subscribe [:link-preview/raw-text text])])
  (fn [[text] [_ _ base-url]]
    (util/external-urls text base-url)))

(r/reg-sub
  :link-preview/state
  (fn [db _]
    (get-in db [:state :link-preview]
            {:containers {}
             :prefetch-queue []})))

(r/reg-sub
  :link-preview/containers
  :<- [:link-preview/state]
  (fn [state _]
    (:containers state)))

(r/reg-sub
  :link-preview/link-count
  :<- [:link-preview/containers]
  (fn [containers _]
    (reduce + (map :count (vals containers)))))
