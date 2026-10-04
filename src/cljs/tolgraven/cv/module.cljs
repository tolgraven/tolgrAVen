(ns tolgraven.cv.module
  (:require
    [tolgraven.cv.pages :as pages]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.react :as rf]
    [tolgraven.cv.views :as view]))

(def spec
  {:content (get content-contract/module-content :cv [])
   :id :cv
   :pages pages/spec
   :view {:page #'view/page}})
