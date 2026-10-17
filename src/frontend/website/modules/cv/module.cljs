(ns website.modules.cv.module
  (:require
    [website.modules.cv.pages :as pages]
    [website.react :as r]
    [website.modules.cv.views :as view]))

(def spec
  {:bundle/depends-on #{:main :carousel}
   :icons ["solid/graduation-cap" "solid/briefcase" "solid/heart"
           "solid/code" "solid/calculator" "solid/globe"]
   :content [:cv]
   :pages pages/spec
   :view {:page #'view/<page>}})
