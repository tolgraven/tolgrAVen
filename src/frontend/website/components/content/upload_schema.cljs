(ns website.components.content.upload-schema
  "Measured byte progress shared by the upload row and its event owner.")

(def progress
  [:and [:map [:loaded [:int {:min 0, :max 9007199254740991}]]
               [:total [:int {:min 1, :max 9007199254740991}]]]
   [:fn (fn [{:keys [loaded total]}]
          (and (number? loaded) (number? total) (<= loaded total)))]])
