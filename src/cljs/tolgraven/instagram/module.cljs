(ns tolgraven.instagram.module
  (:require
    [tolgraven.instagram.events]
    [tolgraven.instagram.subs]
    [tolgraven.instagram.views :as view]))

(def spec
  {:id :instagram
   :view {:view #'view/instagram}})
