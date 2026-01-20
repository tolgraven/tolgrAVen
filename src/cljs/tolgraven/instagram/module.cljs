(ns tolgraven.instagram.module
  (:require
    [re-frame.core :as rf]
    [tolgraven.instagram.events]
    [tolgraven.instagram.subs]
    [tolgraven.instagram.views :as view]))

(def spec
  {:id :instagram
   :view {:view #'view/instagram}
   :init #(rf/dispatch [:instagram/init])})
