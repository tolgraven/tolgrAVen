(ns tolgraven.modules.link-preview.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [tolgraven.modules.link-preview.events]
    [tolgraven.modules.link-preview.subs]
    [tolgraven.modules.link-preview.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :link-preview [])
   :id :link-preview
   :view {:view #'view/<link-preview>}})
