(ns tolgraven.components.page-shell
  "Generic page fallback driven by module-local page declarations."
  (:require [tolgraven.macros :refer-macros [defc defpage]]
            [tolgraven.macros :as m]
            [tolgraven.loader]
            [tolgraven.component.loading :as loading]
            [tolgraven.component.restore :as restore]
            [tolgraven.components.image.sources :as image-sources]
            [tolgraven.page :as page]
            [tolgraven.react :as r]
            [tolgraven.components.ui.layout :as ui-layout]))

(defc <content> [{:keys [loading-view loading-args loading-prefab loading-class placeholder-background] :as spec}]
  (if loading-view
    (m/<> (assoc loading-view :skeleton {:class loading-class}) loading-args)
    [loading/<placeholder>
   {:loading-tag :section
    :classes (or loading-class "ssr-skeleton")
    :loading-prefab (or loading-prefab :article)
    :loading-props (cond-> (select-keys spec [:lines :avatar?])
                     placeholder-background (assoc :style {:background-image placeholder-background}
                                                   :data-image-placeholder true))}]))

(defpage <page> [{:keys [heading] :as spec}]
  (restore/use-readiness! false)
  (let [route @(r/subscribe [:common/route])
        sources (map #(deref (r/subscribe [:content %]))
                     (page/critical-image-paths (:data route)))
        background (some image-sources/placeholder-background sources)]
    (if heading
      [ui-layout/<with-heading> heading [<content> spec]]
      [<content> (assoc spec :placeholder-background background)])))
