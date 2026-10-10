(ns tolgraven.modules.contact.module
  (:require [tolgraven.modules.contact.views :as views]))

(def spec
  {:id :contact
   :styles ["/css/tolgraven/modules/contact.min.css"]
   :ssr-styles :deferred
   :view {:popup #'views/<contact-form-popup>}})
