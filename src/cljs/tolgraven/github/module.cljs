(ns tolgraven.github.module
  (:require
    [tolgraven.github.events]
    [tolgraven.github.subs]
    [tolgraven.github.views :as view]))

(def spec
  {:id :github
   :view {:view #'view/commits}})
