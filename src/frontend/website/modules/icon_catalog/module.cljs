(ns website.modules.icon-catalog.module)

(def spec
  {:id :icon-catalog
   :styles ["/css/website/modules/icon-catalog.min.css"]
   :ssr-styles :deferred
   :view {:empty (fn [] nil)}})
