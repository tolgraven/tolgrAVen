(ns tolgraven.modules.markdown-components.catalog
  "Public lookup retains only canonical identities and lazy module owners."
  #?(:cljs (:require [shadow.lazy]
                     [tolgraven.component.registry :as registry]))
  #?(:cljs (:require-macros [tolgraven.build.authoring :refer [owner-catalog legacy-catalog]])))

#?(:cljs
   (do
     (def owners (owner-catalog))
     (def by-name (into {} (map (fn [[identity owner]] [identity {:name identity, :owner owner}])) owners))))

#?(:browser (registry/register-public-resolvers! (legacy-catalog)))
