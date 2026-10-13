(ns website.modules.cv.module
  {:bundle/depends-on #{:main :carousel}}
  (:require
    [website.modules.cv.pages :as pages]
    [website.content.contract :as content-contract]
    [website.react :as r]
    [website.modules.cv.views :as view]))

(def spec
  {:content [:cv]
   :depends (get content-contract/module-dependencies :cv [])
   :id :cv
   :styles ["/css/tolgraven/modules/cv.min.css"]
   :pages pages/spec
   :view {:page #'view/<page>}})
