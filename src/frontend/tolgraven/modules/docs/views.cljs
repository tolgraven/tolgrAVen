(ns tolgraven.modules.docs.views
  (:require
    [tolgraven.component.registry]
    [tolgraven.modules.docs.pages :as pages]
    [tolgraven.macros :refer-macros [defc defpage]]
    [reagent.core :as r]
    [tolgraven.react :as rf]
    [clojure.string :as string]
    [tolgraven.components.ui :as ui]))

(defn page-links
  "Rewrite generated Codox filenames before rendering. External URLs, root
   URLs and fragment-only links retain their original targets."
  [html]
  (string/replace html #"(href=[\"'])([^\"']+)([\"'])"
    (fn [[original before href after]]
      (if-let [[_ doc suffix] (re-matches #"(?:\./)?([A-Za-z0-9_.-]+)\.html([?#].*)?" href)]
        (str before "/docs/codox/" doc suffix after)
        original))))

(defc <doc-page> "Display a codox page"
  {:depends (fn [page] [(pages/document-dependency page)])
   :loading-tag :div.docs :loading-prefab :text}
  [page]
  (let [html @(rf/subscribe [:docs/page-html page])]
    [:div.docs
     (if html
       ;; This is generated, trusted documentation HTML, an opaque React leaf.
       ;; Transform its content before rendering, never walk and rewrite live DOM.
       [:div.codox {:dangerouslySetInnerHTML (r/unsafe-html (page-links html))}]
       [ui/<loading-spinner> true :massive])]))

(defpage <page>
  ;; Only the heading/framing is CMS content; <doc-page> declares backend HTML.
  {:depends [{:source :strapi :keys [:docs]}]
   :loading-tag :section.docs :loading-prefab :text}
  []
  [ui/<with-heading> [:docs :heading]
   [:section.docs.solid-bg.hi-z.noborder.fullwide
    [<doc-page> @(rf/subscribe [:docs/current-page])]]])
