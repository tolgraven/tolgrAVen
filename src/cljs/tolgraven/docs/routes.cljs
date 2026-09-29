(ns tolgraven.docs.routes
  (:require [re-frame.core :as rf]))

;; Route metadata stays in the main bundle; views are resolved by the module loader.
(def routes
  ["docs"
      ["" {:name   :docs
           :module :docs
           :page   :page
           :controllers [{:start (fn [_]
                                   (rf/dispatch [:docs/get "index"])
                                   (rf/dispatch [:docs/set-page "index"]))}]}]
      ["/codox/:doc"
       {:name   :docs-codox-page
        :module :docs
        :page   :page
        :controllers
        [{:parameters {:path [:doc]}
          :start      (fn [{:keys [path]}]
                        (rf/dispatch [:docs/get (:doc path)])
                        (rf/dispatch [:docs/set-page (:doc path)]))}]}]])
