(ns website.modules.cv.pages
  "Page declarations independent of the module implementation."
  #?(:cljs (:require [website.react :as r])))

#?(:cljs (def controllers
  {:cv [{:stop (fn [_] (r/dispatch [:state [:fullscreen :cv] false]))}]}))

(def spec
  ;; Native Reitit routes, with shared data inherited by each child page.
  [["/cv" {:name :cv :module :cv :page :page :ssr true
           :depends [{:source :strapi :keys [:cv]}]
           #?@(:cljs [:controllers (:cv controllers)])}]])
