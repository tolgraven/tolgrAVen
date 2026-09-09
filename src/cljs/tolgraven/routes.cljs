(ns tolgraven.routes)

(defn external-http-url?
  "True when href resolves to an HTTP(S) URL on a different origin."
  [href base-url]
  (try
    (let [url (js/URL. href base-url)
          base (js/URL. base-url)]
      (and (#{"http:" "https:"} (.-protocol url))
           (not= (.-origin url) (.-origin base))))
    (catch :default _
      false)))
