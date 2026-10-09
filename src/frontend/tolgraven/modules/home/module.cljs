(ns tolgraven.modules.home.module
  (:require [tolgraven.modules.home.pages :as pages]
            [tolgraven.modules.home.views :as views]))

(def spec
  {:id :home
   :pages pages/spec
   :styles ["/css/tolgraven/modules/home.min.css"]
   :view {:page #'views/<auto>}})
