(ns website.modules.home.module
  {:bundle/depends-on #{:main :carousel}}
  (:require [website.modules.home.pages :as pages]
            [website.modules.home.views :as views]
            [website.modules.home.layout :as layout]))

(def spec
  {:content [:intro :services :moneyshot :story :interlude :soundcloud :gallery]
   :id :home
   :pages pages/spec
   :styles ["/css/tolgraven/modules/home.min.css"
            "/css/tolgraven/modules/monospace.min.css"
            "/css/tolgraven/modules/markdown.min.css"]
   :view {:page #'views/<auto>
          :float-image #'layout/<float-img>
          :text-images #'layout/<auto-layout-text-imgs>}})
