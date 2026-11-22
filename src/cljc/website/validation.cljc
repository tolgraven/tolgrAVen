(ns website.validation
  "Stable validation boundary. Browser releases install the Malli engine lazily."
  (:require [clojure.string :as string]
            #?(:cljs [website.async :as a])
            #?(:clj [website.validation.malli :as impl]
               :dev [website.validation.malli :as impl]
               :ssr [website.validation.malli :as impl])))

(defonce *engine (atom #?(:clj impl/functions :dev impl/functions :ssr impl/functions :default nil)))
#?(:cljs (defonce ^:private *pending (atom nil)))
(defn ready? [] (some? @*engine))
(defn install-engine! [engine]
  (reset! *engine engine)
  #?(:cljs (when-let [{:keys [resolve!]} @*pending]
             (reset! *pending nil)
             (resolve! nil)))
  nil)
#?(:cljs
   (defn when-ready!
     "Wait without acquiring code; boot/navigation own acquisition."
     []
     (if (ready?)
       (a/resolved nil)
       (:promise (or @*pending (reset! *pending (a/deferred)))))))

(defn- operation [key]
  (or (get @*engine key)
      (throw (ex-info "Validation engine is not ready" {:type :validation/not-ready}))))
(defn compiled [schema] ((operation :compiled) schema))
(defn decode [schema value coercion]
  (if coercion ((operation :decode) schema value coercion) value))
(defn problems [explanation] ((operation :problems) explanation))
(defn explain [schema value] ((operation :explain) schema value))
(defn check! [contract schema value] ((operation :check!) contract schema value))
(defn message [issues]
  (string/join "\n" (map (fn [{:keys [path message]}]
                           (str (pr-str path) " — " message)) issues)))
(defn enabled? [config development?]
  (let [value (if (contains? config :validation-enabled)
                (:validation-enabled config) (get-in config [:validation :enabled]))]
    (if (nil? value) (boolean development?)
        (contains? #{true "true" "1" 1} value))))
