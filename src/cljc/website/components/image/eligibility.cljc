(ns website.components.image.eligibility
  "Progressive display eligibility shared by bundled and explicit managed assets.")

(defn eligible?
  "Only substantial originals benefit from acquiring a second, preview rendition.
   Explicit caller/catalog overrides remain separate from this default."
  [{:keys [width height bytes]}]
  (and (pos-int? width) (pos-int? height) (nat-int? bytes)
       (> width 320)
       (>= (max width height) 960)
       (>= bytes (* 64 1024))))
