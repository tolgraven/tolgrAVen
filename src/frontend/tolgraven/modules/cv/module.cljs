(ns tolgraven.modules.cv.module
  (:require
    [tolgraven.modules.cv.pages :as pages]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.modules.cv.views :as view]))

(def spec
  {:depends (get content-contract/module-dependencies :cv [])
   :id :cv
   :styles ["/css/tolgraven/modules/cv.min.css"]
   :pages pages/spec
   :view {:page #'view/<page>}})
