(ns tolgraven.validation.malli
  "Shared, value-redacting Malli validation. Runtime policy belongs to adapters."
  (:require [tolgraven.schema.registry]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [clojure.string :as string]
            #?(:dev [tolgraven.dev.values :as details])))

(defonce ^:private *compiled (atom {}))
(def compiled-cache-limit 256)
(defn compiled [schema]
  (or (get @*compiled schema)
      (let [schema* (m/schema schema)
            compiled {:valid? (m/validator schema*)
                      :explain (m/explainer schema*)
                      :string-decoder (delay (m/decoder schema* mt/string-transformer))
                      :json-decoder (delay (m/decoder schema* mt/json-transformer))}]
        ;; Declaration schemas are stable, but composed instance schemas can
        ;; change throughout a long session. Drop the previous cache generation
        ;; at the bound instead of retaining every historical schema forever.
        (swap! *compiled #(assoc (if (< (count %) compiled-cache-limit) % {}) schema compiled))
        compiled)))

(defn decode
  "Explicit normalization is identical with checks on or off. Never guess a
   transformer from a value; ordinary event/subscription arguments retain types."
  [schema value coercion]
  (case coercion
    nil value
    :string ((force (:string-decoder (compiled schema))) value)
    :json ((force (:json-decoder (compiled schema))) value)
    (throw (ex-info "Unknown schema coercion" {:coercion coercion}))))

(defn problems [explanation]
  ;; Public issue maps omit values. Only development metadata includes bounded,
  ;; redacted details for the local schema inspector.
  (let [issues (mapv (fn [error] {:path (vec (:in error))
                                 :message (me/error-message error)})
                     (:errors explanation))]
    #?(:dev (with-meta issues {:dev/issues (mapv #(details/issue % (m/form (:schema %)))
                                               (take 20 (:errors explanation)))})
       :default issues)))

(defn explain [schema value]
  (let [{:keys [valid? explain]} (compiled schema)]
    (when-not (valid? value) (problems (explain value)))))

(defn message [issues]
  (string/join "\n" (map (fn [{:keys [path message]}]
                           (str (pr-str path) " — " message)) issues)))

(defn check! [contract schema value]
  (let [issues (explain schema value)]
    (when (seq issues)
      (throw (ex-info (str "Invalid " contract "\n" (message issues))
                      {:type :validation/failed :contract contract :issues issues}))))
  value)

(defn enabled?
  "An explicit environment/config override wins over the development default."
  [config development?]
  (let [value (if (contains? config :validation-enabled)
                (:validation-enabled config) (get-in config [:validation :enabled]))]
    (if (nil? value) (boolean development?)
        (contains? #{true "true" "1" 1} value))))

(def functions
  {:compiled compiled
   :decode decode
   :problems problems
   :explain explain
   :check! check!})
