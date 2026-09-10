(ns tolgraven.views.home
  (:require [re-frame.core :as rf]
            [tolgraven.views.auto :as auto]))

(def routes
  [[""
      {:name        :home
       :view        #'auto/auto
       :controllers [{:start (fn [{:keys [_]}]
                               (rf/dispatch [:state [:is-personal] false])
                               (rf/dispatch [:page/init-home]))}]}]
     ["about"
      {:name        :about
       :view        #'auto/auto
       :controllers [{:start (fn [_]
                               (rf/dispatch [:scroll/to "about" 700]))}]}]
     ["services"
      {:name        :services
       :view        #'auto/auto
       :controllers [{:start (fn [_]
                               (rf/dispatch [:scroll/to "main" 700]) ; hack because stickied so "already there"...
                               (rf/dispatch [:scroll/to "section-services" 1300]))}]}]
     ["hire"
      {:name        :hire
       :view        #'auto/auto
       :controllers [{:start (fn [_]
                               (rf/dispatch [:scroll/to "bottom" 700]))}]}]])
