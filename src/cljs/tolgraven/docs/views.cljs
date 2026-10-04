(ns tolgraven.docs.views
  (:require
   [reagent.core :as r]
   [tolgraven.react :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]))

(defn page-links
  "Rewrite generated Codox filenames before rendering. External URLs, root
   URLs and fragment-only links retain their original targets."
  [html]
  (string/replace html #"(href=[\"'])([^\"']+)([\"'])"
    (fn [[original before href after]]
      (if-let [[_ doc suffix] (re-matches #"(?:\./)?([A-Za-z0-9_.-]+)\.html([?#].*)?" href)]
        (str before "/docs/codox/" doc suffix after)
        original))))

(defn doc-page "Display a codox page"
  []
  (let [html @(rf/subscribe [:docs/page-html])]
    [:div.docs
     (if html
       ;; This is generated, trusted documentation HTML, an opaque React leaf.
       ;; Transform its content before rendering, never walk and rewrite live DOM.
       [:div.codox {:dangerouslySetInnerHTML (r/unsafe-html (page-links html))}]
       [ui/loading-spinner true :massive])]))

(defn page []
  [ui/with-heading [:docs :heading]
   [:section.docs.solid-bg.hi-z.noborder.fullwide
    [doc-page]]])

