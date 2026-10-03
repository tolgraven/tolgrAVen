(ns tolgraven.github.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [re-frame.core :as rf]
    [tolgraven.github.events]
    [tolgraven.github.subs]
    [tolgraven.github.views :as view]))

(def spec
  {:content (get content-contract/module-content :github [])
   :id :github
   :view {:view #'view/commits}
   :init #(rf/dispatch [:github/init])})
