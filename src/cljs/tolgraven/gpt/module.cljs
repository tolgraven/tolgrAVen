(ns tolgraven.gpt.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.gpt.events]
    [tolgraven.gpt.subs]
    [tolgraven.gpt.views :as view]))

(def spec
  {:content (get content-contract/module-content :gpt [])
   :id :gpt
   :view {:view #'view/<threads>}})
