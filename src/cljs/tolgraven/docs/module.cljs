(ns tolgraven.docs.module
  (:require
    [re-frame.core :as rf]
    [tolgraven.docs.events]
    [tolgraven.docs.subs]
    [tolgraven.docs.views :as view]))

(def spec
  {:id :docs
   :view {:page #'view/page}
   :init #(rf/dispatch [:docs/init])})
