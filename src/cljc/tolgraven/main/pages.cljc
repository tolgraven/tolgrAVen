(ns tolgraven.main.pages
  "Page declarations independent of the module implementation."
  #?(:cljs (:require [tolgraven.react :as rf])))

#?(:cljs (def controllers
  {:home [{:start (fn [_]
                    (rf/dispatch [:state [:is-personal] false])
                    (rf/dispatch [:page/init-home]))}]
   :about [{:start (fn [_] (rf/dispatch [:scroll/to "about" 700]))}]
   :services [{:start (fn [_]
                        (rf/dispatch [:scroll/to "main" 700])
                        (rf/dispatch [:scroll/to "section-services" 1300]))}]
   :hire [{:start (fn [_] (rf/dispatch [:scroll/to "bottom" 700]))}]}))

(def spec
  ;; Native Reitit routes, with shared data inherited by each child page.
  [["" {:module :main :page :page :ssr true :data-source :content :kind :landing}
    ["/" {:name :home #?@(:cljs [:controllers (:home controllers)])}]
    ["/about" {:name :about #?@(:cljs [:controllers (:about controllers)])}]
    ["/services" {:name :services #?@(:cljs [:controllers (:services controllers)])}]
    ["/hire" {:name :hire #?@(:cljs [:controllers (:hire controllers)])}]]])
