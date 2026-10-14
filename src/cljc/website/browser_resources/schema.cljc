(ns website.browser-resources.schema
  "Public optional-script settings shared by the HTTP boundary and browser.")

(def google-id [:and :string [:re #"^G-[A-Z0-9]+$"]])
(def settings
  [:map {:closed true}
   [:analytics [:map {:closed true} [:?google-id google-id]]]])
