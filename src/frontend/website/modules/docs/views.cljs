(ns website.modules.docs.views
  (:require
    [website.component.registry]
    [website.modules.docs.pages :as pages]
    [website.macros :refer-macros [defc defpage]]
    [website.react :as r]

    [clojure.string :as string]
    [website.components.ui.layout :as ui-layout]
    [website.components.ui.loading :as ui-loading]))

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
  (let [html @(r/subscribe [:docs/page-html page])]
    [:div.docs
     (if html
       ;; This is generated, trusted documentation HTML, an opaque React leaf.
       ;; Transform its content before rendering, never walk and rewrite live DOM.
       [:div.codox {:dangerouslySetInnerHTML (r/unsafe-html (page-links html))}]
       [ui-loading/<loading-spinner> true :massive])]))

(defpage <page>
  ;; Only the heading/framing is CMS content; <doc-page> declares backend HTML.
  {:depends [{:source :strapi :keys [:docs]}]
   :loading-tag :section.docs :loading-prefab :text}
  []
  [ui-layout/<with-heading> [:docs :heading]
   [:section.docs.solid-bg.hi-z.noborder.fullwide
    [<doc-page> @(r/subscribe [:docs/current-page])]]])
