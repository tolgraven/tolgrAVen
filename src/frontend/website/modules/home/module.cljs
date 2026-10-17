(ns website.modules.home.module
  (:require [website.modules.home.pages :as pages]
            [website.modules.home.views :as views]
            [website.modules.home.layout :as layout]
            [website.modules.home.schema :as schema]))

(def spec
  {:bundle/depends-on #{:main :carousel}
   :content [:intro :services :moneyshot :story :interlude :soundcloud :gallery]
   :server {:search [{:kind "site-section"
                      :preview :home-preview/section
                      :content [:intro :services :moneyshot :story :interlude :soundcloud :gallery]}]}
   :pages pages/spec
   :db-schema {[:state :gallery] schema/gallery}
   :view {:page #'views/<auto>
          :dynamic-page #'views/<dynamic-page>
          :float-image #'layout/<float-img>
          :text-images #'layout/<auto-layout-text-imgs>}})
