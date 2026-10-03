(ns tolgraven.cv.module
  (:require
    [tolgraven.content.contract :as content-contract]
    [re-frame.core :as rf]
    [tolgraven.cv.views :as view]))

(def spec
  {:content (get content-contract/module-content :cv [])
   :id :cv
   :view {:page #'view/page}})
