(ns website.components.image.schema
  "Portable metadata shared by bundled and managed images.")

(def placeholder
  [:map {:closed true}
   [:colors [:vector {:min 3 :max 3} [:and :string [:re #"^#[0-9a-f]{6}$"]]]]])
