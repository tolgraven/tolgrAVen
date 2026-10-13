(ns website.modules.carousel.module
  (:require [website.modules.carousel.events]
            [website.modules.carousel.subs]
            [website.modules.carousel.views :as views]))

(def spec
  {:id :carousel
   :styles ["/css/tolgraven/modules/carousel.min.css"]
   :view {:indices #'views/<carousel-idx-btns>
          :three #'views/<carousel>
          :normal #'views/<carousel-normal>}})
