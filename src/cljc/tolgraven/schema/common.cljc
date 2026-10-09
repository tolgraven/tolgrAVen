(ns tolgraven.schema.common
  "Small shared types. Open maps still validate every declared field; only
   explicitly extensible payloads use :any."
  #?(:cljs (:require [tolgraven.schema.registry])))

(def id [:or :string :int :keyword])
(def nonnegative [:int {:min 0}])
(def positive [:int {:min 1}])
(def milliseconds [:and number? [:>= 0]])
(def path [:vector {:min 1} :any])
(def event [:and vector? [:cat :keyword [:* :any]]])
(def text [:string {:min 1}])
(def strings [:sequential :string])
(def named [:or :string :keyword])
(defn optional-map [fields]
  (into [:map] (map (fn [[key schema]] [key {:optional true} schema])) fields))
(def error (optional-map {:title :string :message :string :status [:or :int :keyword]
                          :issues [:sequential [:map [:path [:vector :any]] [:message :string]]]}))
(def query-params [:map-of named [:or :string number? :boolean :nil [:sequential :string]]])
(def hiccup
  "A component may return Hiccup, a scalar, a sequence of children or nil.
   Native React elements are accepted only at the React adapter boundary."
  [:or :nil :string number? :boolean [:vector :any] [:sequential :any]])

(def derefable
  [:fn {:error/message "must be a dereferenceable value"}
   #?(:clj #(instance? clojure.lang.IDeref %)
      :cljs #(satisfies? IDeref %))])
