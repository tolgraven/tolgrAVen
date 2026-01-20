(ns tolgraven.search.module
  (:require
    [re-frame.core :as rf]
    [tolgraven.search.events]
    [tolgraven.search.subs]
    [tolgraven.search.views :as view]))

(def spec
  {:id :search
   :view {:view #'view/ui
          :button #'view/button}
   :init #(rf/dispatch [:search/init])})
