(ns tolgraven.modules.carousel.subs
  (:require [tolgraven.react :as rf]))

(rf/reg-sub :carousel/index
  (fn [[_ id]] (rf/subscribe [:state [:carousel id :index]]))
  (fn [index _] (or index 0)))
