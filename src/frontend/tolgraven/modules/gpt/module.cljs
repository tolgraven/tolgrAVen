(ns tolgraven.modules.gpt.module
  {:bundle/depends-on #{:main :user}}
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.modules.gpt.events]
    [tolgraven.modules.gpt.subs]
    [tolgraven.modules.gpt.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :gpt [])
   :id :gpt
   :view {:view #'view/<threads>}})
