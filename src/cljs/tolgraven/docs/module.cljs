(ns tolgraven.docs.module
  (:require
    [tolgraven.docs.events]
    [tolgraven.docs.subs]
    [tolgraven.docs.views :as view]))

(def spec
  {:id :docs
   :view {:page #'view/page}})
