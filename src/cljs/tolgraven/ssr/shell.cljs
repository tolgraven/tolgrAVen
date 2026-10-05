(ns tolgraven.ssr.shell
  "Generic page fallback driven by module-local page declarations."
  (:require [tolgraven.macros :refer-macros [defc]]
            [tolgraven.component.loading :as loading]
            [tolgraven.ui :as ui]))

(defc <content> [{:keys [lines avatar?] :or {lines 8 avatar? true}}]
  [:section.ssr-skeleton {:role "status" :aria-label "Loading page content"}
   [loading/<h1>]
   (when avatar? [loading/<avatar>])
   [loading/<lines> {:count lines}]
   [:span.sr-only "Loading page content…"]])

(defc <page> [{:keys [heading] :as spec}]
  (if heading
    [ui/<with-heading> heading [<content> spec]]
    [<content> spec]))
