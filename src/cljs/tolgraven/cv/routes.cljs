(ns tolgraven.cv.routes
  (:require [re-frame.core :as rf]))

;; Route metadata stays in the main bundle; views are resolved by the module loader.
(def routes
  ["cv"
      {:name        :cv
       :module      :cv
       :page        :page
       :controllers [{:stop (fn [_]
                              (rf/dispatch [:state [:fullscreen :cv] false]))}]}])
