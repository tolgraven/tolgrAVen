(ns tolgraven.docs.pages
  "Page declarations independent of the module implementation."
  #?(:cljs (:require [tolgraven.react :as rf])))

#?(:cljs (do
  (defn- activate! [page]
    (rf/dispatch [:docs/get page])
    (rf/dispatch [:docs/set-page page]))
  (def controllers
    {:docs [{:start (fn [_] (activate! "index"))}]
     :docs-codox-page [{:parameters {:path [:doc]}
                       :start (fn [{:keys [path]}] (activate! (:doc path)))}]})))

(def spec
  ;; Native Reitit routes, with shared data inherited by each child page.
  [["/docs" {:module :docs :page :page :ssr true :streaming false :data-source :docs}
    ["" {:name :docs :selection {:doc "index"}
         #?@(:cljs [:controllers (:docs controllers)])}]
    ["/codox/:doc" {:name :docs-codox-page
                    :ssr-parameters {:doc {:from :doc :type :document}}
                    #?@(:cljs [:controllers (:docs-codox-page controllers)])}]]])
