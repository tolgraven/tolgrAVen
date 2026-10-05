(ns tolgraven.gpt.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.gpt.events]
    [tolgraven.gpt.subs]
    [tolgraven.gpt.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :gpt [])
   :id :gpt
   :view {:view #'view/<threads>}})
