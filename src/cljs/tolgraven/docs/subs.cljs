(ns tolgraven.docs.subs
  (:require
   [re-frame.core :as rf]
   [clojure.string :as string]
   [clojure.walk :as walk]))


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
 :<- [:docs/get]
 :<- [:docs/state]
 :<- [:docs/current-page]
 (fn [[docs state current-page] [_ page]]
   (let [page (or page current-page "01-intro")]
     (get-in docs [page]))))
