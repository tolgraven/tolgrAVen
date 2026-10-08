(ns tolgraven.modules.cv.pages
  "Page declarations independent of the module implementation."
  #?(:cljs (:require [tolgraven.react :as rf])))

#?(:cljs (def controllers
  {:cv [{:stop (fn [_] (rf/dispatch [:state [:fullscreen :cv] false]))}]}))

(def spec
  ;; Native Reitit routes, with shared data inherited by each child page.
  [["/cv" {:name :cv :module :cv :page :page :ssr true
           :depends [{:source :strapi :keys [:cv]}]
           #?@(:cljs [:controllers (:cv controllers)])}]])
