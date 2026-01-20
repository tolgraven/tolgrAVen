(ns tolgraven.docs.module
  (:require
    [re-frame.core :as rf]
    [ajax.core :as ajax]
    [tolgraven.docs.events]
    [tolgraven.docs.subs]
    [tolgraven.docs.views :as view]))

(def spec
  {:id :docs
   :view {:page #'view/page}
   :init #(rf/dispatch [:http/get {:uri             "/api/docs"
                                   :response-format (ajax/raw-response-format)}
                        [:content [:docs :md]]])})
