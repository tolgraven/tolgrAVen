(ns tolgraven.components.iframe)

(def sandbox-by-trust
  {:trusted "allow-forms allow-scripts"
   :user ""
   :untrusted ""})

(defn <iframe>
  "Generic sandboxed iframe. Loading and presentation belong to its caller."
  [{:keys [label on-load src trust]
    :or {trust :untrusted}}]
  [:iframe
   {:aria-label label
    :on-load on-load
    :referrer-policy "no-referrer"
    :sandbox (get sandbox-by-trust trust "")
    :src src}])
