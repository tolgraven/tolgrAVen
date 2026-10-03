(ns tolgraven.link-preview.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.link-preview.events]
    [tolgraven.link-preview.subs]
    [tolgraven.link-preview.views :as view]))

(def spec
  {:content (get content-contract/module-content :link-preview [])
   :id :link-preview
   :view {:view #'view/<link-preview>}})
