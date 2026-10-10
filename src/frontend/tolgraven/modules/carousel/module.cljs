(ns tolgraven.modules.carousel.module
  (:require [tolgraven.modules.carousel.events]
            [tolgraven.modules.carousel.subs]
            [tolgraven.modules.carousel.views :as views]))

(def spec
  {:id :carousel
   :styles ["/css/tolgraven/modules/carousel.min.css"]
   :view {:indices #'views/<carousel-idx-btns>
          :three #'views/<carousel>
          :normal #'views/<carousel-normal>}})
