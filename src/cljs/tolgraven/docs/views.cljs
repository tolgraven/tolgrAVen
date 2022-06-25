(ns tolgraven.docs.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.views-common :as views]
   [tolgraven.util :as util :refer [at]]))

(defn page "Display a codox page"
  []
  (let [html @(rf/subscribe [:docs/page-html])]
   [:div.docs
     [:div.codox
      {:ref (fn [el]
              (doseq [el (.querySelectorAll js/document ".codox a")]
                (set! (.-href el) ; XXX gotta keep it from doing this to github links!
                      (str "/docs/codox/"
                           (-> (.-href el)
                               (string/replace  #"http://.*/" "")
                               (string/replace  #"\.html" ""))))))
       :dangerouslySetInnerHTML {:__html html}}]] ))
