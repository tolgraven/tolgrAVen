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
   [:section.docs
     {:dangerouslySetInnerHTML {:__html html}}] ))
