(ns tolgraven.loader.code
  "Platform-specific Shadow loadables; JVM tooling and SSR use eager specs."
  #?(:cljs (:require [shadow.lazy :as lazy]))
  #?(:cljs (:require-macros [tolgraven.macros :as m])))

(def modules
  #?(:browser (merge (m/make-modules "tolgraven" [:blog
                                                 :link-preview
                                                 :search
                                                 :user
                                                 :chat
                                                 :github
                                                 :cv
                                                 :docs
                                                 :gpt
                                                 :strava
                                                 :instagram])
                    {:test (lazy/loadable tolgraven.experiments/spec)})
     :default {}))

