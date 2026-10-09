(ns tolgraven.validation
  "Shared, value-redacting Malli validation. Runtime policy belongs to adapters."
  (:require #?(:cljs [tolgraven.schema.registry])
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [clojure.string :as string]))

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
  ;; Never retain input values: state and request bodies can contain credentials.
  (mapv (fn [error] {:path (vec (:in error))
                     :message (me/error-message error)})
        (:errors explanation)))

(defn explain [schema value]
  (let [{:keys [valid? explain]} (compiled schema)]
    (when-not (valid? value) (problems (explain value)))))

(defn message [issues]
  (string/join "\n" (map (fn [{:keys [path message]}]
                           (str (pr-str path) " — " message)) issues)))

(defn check! [contract schema value]
  (when-let [issues (seq (explain schema value))]
    (throw (ex-info (str "Invalid " contract "\n" (message issues))
                    {:type :validation/failed :contract contract :issues (vec issues)})))
  value)

(defn enabled?
  "An explicit environment/config override wins over the development default."
  [config development?]
  (let [value (if (contains? config :validation-enabled)
                (:validation-enabled config) (get-in config [:validation :enabled]))]
    (if (nil? value) (boolean development?)
        (contains? #{true "true" "1" 1} value))))
