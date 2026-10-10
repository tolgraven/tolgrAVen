(ns tolgraven.component.instrumentation
  "A build boundary: production and Node never require development profiling."
  #?(:cljs (:require [tolgraven.component.markup :as markup]
                     [tolgraven.diagnostics.consumer :as consumer]))
  #?(:dev (:require [tolgraven.component.dev-instrumentation :as dev])))

(defn register-dependencies-resolver! [resolve!]
  #?(:dev (dev/register-dependencies-resolver! resolve!) :default nil))
(defn register-path-resolver! [resolve!]
  #?(:dev (dev/register-path-resolver! resolve!) :default nil))

(defn render! [definition args render]
  #?(:cljs (consumer/render! definition args render) :default (render)))

(defn identity-for [definition] [(:ns definition) (:name definition)])

(defn instrument
  ([definition form]
   #?(:dev (dev/instrument definition form)
      :cljs (if (fn? form) form (markup/normalize-form form))
      :default form))
  ([definition form args key]
   #?(:dev (dev/instrument definition form args key)
      :cljs (if (fn? form) form (markup/normalize-form form))
      :default form)))
