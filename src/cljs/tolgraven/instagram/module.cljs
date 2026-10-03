(ns tolgraven.instagram.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [re-frame.core :as rf]
    [tolgraven.instagram.events]
    [tolgraven.instagram.subs]
    [tolgraven.instagram.views :as view]))

(def spec
  {:content (get content-contract/module-content :instagram [])
   :id :instagram
   :view {:view #'view/instagram}
   :init #(rf/dispatch [:instagram/init])})
