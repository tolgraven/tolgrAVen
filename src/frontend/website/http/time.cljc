(ns website.http.time
  "Java-time codecs for development prototypes. Production uses standard Transit."
  #?(:dev (:require [luminus-transit.time :as time])))

(def read-options #?(:dev time/time-deserialization-handlers :default {}))
(def write-options #?(:dev time/time-serialization-handlers :default {}))
