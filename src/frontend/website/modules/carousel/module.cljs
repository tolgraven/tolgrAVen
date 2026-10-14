(ns website.modules.carousel.module
  (:require [website.modules.carousel.events]
            [website.modules.carousel.subs]
            [website.modules.carousel.gallery :as gallery]
            [website.modules.carousel.views :as views]))

(def spec
  {:view {:indices #'views/<carousel-idx-btns>
          :three #'views/<carousel>
          :normal #'views/<carousel-normal>
          :gallery-two #'gallery/<gallery-2>
          :gallery-three #'gallery/<gallery-3>}})
