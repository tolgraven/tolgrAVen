(ns website.components.iframe
  (:require
    [website.component.registry]
    [website.macros :refer-macros [defc]]))

(def sandbox-by-trust
  {:trusted "allow-forms allow-scripts"
   :user ""
   :untrusted ""})

(defc <iframe>
  "Generic sandboxed iframe. Loading and presentation belong to its caller."
  [{:keys [label on-load src trust ref style sandbox]
    :or {trust :untrusted}}]
  [:iframe
   {:aria-label label
    :on-load on-load
    :referrer-policy "no-referrer"
    :tab-index -1
    :sandbox (or sandbox (get sandbox-by-trust trust ""))
    :ref ref
    :style style
    :src src}])
