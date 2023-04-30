(ns tolgraven.macros
 (:require [clojure.string :as string])
 #?(:cljs (:require-macros [tolgraven.macros])))

(defmacro handler-fn "Use in event-handlers instead of (fn [e/_]), returns nil so react doesnt get a false and ignore us"
  ([& body]
    `(fn [~'event] ~@body nil)))  ;; force return nil

(defmacro ors "(or), but treats empty string as nil. not working"
  ([] nil)
  ([x]
   (when-not (clojure.string/blank? x)
     x))
  ([x & next]
   `(let [or# ~x]
          (if or# or# (ors ~@next)))))
