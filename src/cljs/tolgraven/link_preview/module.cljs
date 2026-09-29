(ns tolgraven.link-preview.module
  (:require
    [tolgraven.link-preview.events]
    [tolgraven.link-preview.subs]
    [tolgraven.link-preview.views :as view]))

(def spec
  {:id :link-preview
   :view {:view #'view/<link-preview>}})
