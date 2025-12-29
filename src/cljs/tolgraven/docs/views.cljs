(ns tolgraven.docs.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]))

(defn doc-page "Display a codox page"
  []
  (let [html @(rf/subscribe [:docs/page-html])]
   [:div.docs
     (if html
      [:div.codox
      {:ref (fn [el]
              (doseq [el (.querySelectorAll js/document ".codox a")
                      :let [is-github? (string/index-of (.-href el) "github")]]
                (set! (.-href el) ; XXX gotta keep it from doing this to github links!
                      (str (when-not is-github?
                             "/docs/codox/")
                           (if-not is-github?
                             (-> (.-href el)
                               (string/replace  #"http(s)?://.*/" "")
                               (string/replace  #"\.html" ""))
                             (.-href el)))))
              (rf/dispatch [:run-highlighter! el]))
       :dangerouslySetInnerHTML (r/unsafe-html html)}]
      [ui/loading-spinner (not html) :massive])]))

(defn page []
  [ui/with-heading [:docs :heading]
   [:section.docs.solid-bg.hi-z.noborder.fullwide
    [doc-page]]])

