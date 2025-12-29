(ns tolgraven.gpt.module
  (:require
    [tolgraven.gpt.events]
    [tolgraven.gpt.subs]
    [tolgraven.gpt.views :as view]))

(def spec
  {:id :gpt
   :view {:view #'view/threads}})
