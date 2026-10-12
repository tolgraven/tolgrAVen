(ns tolgraven.modules.settings.module
  (:require [tolgraven.modules.settings.views :as views]))

(def spec
  {:id :settings
   :styles ["/css/tolgraven/modules/settings.min.css"]
   :ssr-styles :deferred
   :view {:view #'views/<settings>}})
