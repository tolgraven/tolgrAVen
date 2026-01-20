(ns tolgraven.github.module
  (:require
    [re-frame.core :as rf]
    [tolgraven.github.events]
    [tolgraven.github.subs]
    [tolgraven.github.views :as view]))

(def spec
  {:id :github
   :view {:view #'view/commits}
   :init #(rf/dispatch [:github/init])})
