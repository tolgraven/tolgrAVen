(ns website.modules.home.module
  (:require [website.modules.home.pages :as pages]
            [website.modules.home.views :as views]
            [website.modules.home.layout :as layout]))

(def spec
  {:bundle/depends-on #{:main :carousel}
   :content [:intro :services :moneyshot :story :interlude :soundcloud :gallery]
   :pages pages/spec
   :styles ["/css/website/modules/home.min.css"
            "/css/website/modules/monospace.min.css"
            "/css/website/modules/markdown.min.css"]
   :view {:page #'views/<auto>
          :float-image #'layout/<float-img>
          :text-images #'layout/<auto-layout-text-imgs>}})
