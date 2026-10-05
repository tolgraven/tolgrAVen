(ns tolgraven.ssr.shell
  "Generic page fallback driven by module-local page declarations."
  (:require [tolgraven.macros :refer-macros [defc defpage]]
            [tolgraven.component.loading :as loading]
            [tolgraven.ui :as ui]))

(defc <content> [{:keys [loading-prefab loading-class] :as spec}]
  [loading/<placeholder>
   {:loading-tag :section
    :classes (or loading-class "ssr-skeleton")
    :loading-prefab (or loading-prefab :article)
    :loading-props (select-keys spec [:lines :avatar?])}])

(defpage <page> [{:keys [heading] :as spec}]
  (if heading
    [ui/<with-heading> heading [<content> spec]]
    [<content> spec]))
