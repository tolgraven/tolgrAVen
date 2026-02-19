(ns tolgraven.cv.module
  (:require
    [re-frame.core :as rf]
    [tolgraven.cv.views :as view]))

(def spec
  {:id :cv
   :view {:page #'view/page}})
