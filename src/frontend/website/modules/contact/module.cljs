(ns website.modules.contact.module
  (:require [website.modules.contact.views :as views]))

(def spec
  {:server {:routes 'website.modules.contact.api/routes}
   :id :contact
   :styles ["/css/website/modules/contact.min.css"]
   :ssr-styles :deferred
   :view {:popup #'views/<contact-form-popup>}})
