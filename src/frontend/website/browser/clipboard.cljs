(ns website.browser.clipboard)

(defn ^:async copy!
  "Copy text or reject with :reason :unavailable when the browser has no clipboard."
  [text]
  (if-let [clipboard (when (exists? js/navigator) (.-clipboard js/navigator))]
    (.writeText clipboard text)
    (throw (ex-info "Clipboard unavailable" {:reason :unavailable}))))
