(ns tolgraven.validation.markup
  "Shared Hiccup for server error documents and Reagent fallbacks.")

(defn issues [issues]
  [:dl.validation-issues
   (for [[index {:keys [path message]}] (map-indexed vector issues)]
     ^{:key index}
     [:div.validation-issues__row
      [:dt [:code (pr-str path)]]
      [:dd message]])])
