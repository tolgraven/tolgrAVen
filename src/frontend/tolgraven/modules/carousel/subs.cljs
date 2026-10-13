(ns tolgraven.modules.carousel.subs
  (:require [tolgraven.react :as r]))

(r/reg-sub :carousel/index
  (fn [[_ id]] (r/subscribe [:state [:carousel id :index]]))
  (fn [index _] (or index 0)))
