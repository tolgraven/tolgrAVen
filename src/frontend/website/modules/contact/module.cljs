(ns website.modules.contact.module
  (:require [website.modules.contact.views :as views]))

(def spec
  {:server {:routes 'website.modules.contact.api/routes}
   :ssr-styles :deferred
   :view {:popup #'views/<contact-form-host>}})
