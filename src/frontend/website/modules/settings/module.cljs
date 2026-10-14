(ns website.modules.settings.module
  (:require [website.modules.settings.views :as views]
            [website.modules.settings.events]))

(def spec
  {:id :settings
   :styles ["/css/website/modules/settings.min.css"]
   :ssr-styles :deferred
   :view {:view #'views/<settings>
          :config-table #'views/<config-table>}})
