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
     (if html
      [:div.codox
      {:ref (fn [el]
              (doseq [el (.querySelectorAll js/document ".codox a")]
                (set! (.-href el) ; XXX gotta keep it from doing this to github links!
                      (str (when-not (string/index-of (.-href el) "github")
                             "/docs/codox/")
                           (-> (.-href el)
                               (string/replace  #"http://.*/" "")
                               (string/replace  #"\.html" ""))))))
       :dangerouslySetInnerHTML {:__html html}}]
      [ui/loading-spinner (not html) :massive])]))
