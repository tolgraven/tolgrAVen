(ns tolgraven.component)

(defonce *features (atom {}))

(defn register-feature!
  [id lifecycle]
  (swap! *features assoc id lifecycle))

(defn feature
  [id]
  (get @*features id))
