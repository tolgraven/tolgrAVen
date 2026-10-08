(ns tolgraven.modules.docs.subs
  (:require [tolgraven.react :as rf]))


(rf/reg-sub :docs/get
 (fn [db [_ path]]
   (get-in db (into [:docs] path))))

(rf/reg-sub :docs/state
 (fn [db [_ path]]
   (get-in db (into [:state :docs] path))))

(rf/reg-sub :docs/current-page
 (fn [db [_ _]]
   (get-in db [:state :docs :current-page])))

(rf/reg-sub :docs/previous-page
 (fn [db [_ _]]
   (get-in db [:state :docs :previous-page])))

(rf/reg-sub :docs/page-html
  (fn [db query]
    (let [[_ page] (or (:re-frame/query-v query) query)]
      (get-in db [:docs (or page (get-in db [:state :docs :current-page]) "index")]))))
