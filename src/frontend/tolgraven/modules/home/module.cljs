(ns tolgraven.modules.home.module
  (:require [tolgraven.modules.home.pages :as pages]
            [tolgraven.modules.home.views :as views]
            [tolgraven.modules.home.layout :as layout]))

(def spec
  {:id :home
   :pages pages/spec
   :styles ["/css/tolgraven/modules/home.min.css"]
   :view {:page #'views/<auto>
          :float-image #'layout/<float-img>
          :text-images #'layout/<auto-layout-text-imgs>}})
