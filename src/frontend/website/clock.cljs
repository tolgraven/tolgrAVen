(ns website.clock)

(defn epoch-ms
  "Wall-clock milliseconds used by persisted timestamps and expiry deadlines."
  []
  (js/Date.now))
