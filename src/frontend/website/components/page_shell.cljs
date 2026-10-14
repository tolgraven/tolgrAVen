(ns website.components.page-shell
  "Generic page fallback driven by module-local page declarations."
  (:require [website.macros :refer-macros [defc defpage]]
            [website.macros :as m]
            [website.loader]
            [website.component.loading :as loading]
            [website.component.restore :as restore]
            [website.components.image.sources :as image-sources]
            [website.page :as page]
            [website.react :as r]
            [website.components.ui.layout :as ui-layout]))

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
