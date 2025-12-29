(ns tolgraven.search.module
  (:require
    [tolgraven.search.events]
    [tolgraven.search.subs]
    [tolgraven.search.views :as view]))

(def spec
  {:id :search
   :view {:view #'view/ui
          :button #'view/button}})
