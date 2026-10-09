(ns tolgraven.schema.app-db
  "Partial sections assemble without closing the rest of app-db. Owners can add
   sections; the event adapter checks only changed section references."
  (:require [malli.util :as mu]
            [tolgraven.validation :as validation]))

(defonce ^:private *assembled (atom nil))
(defn schema
  "Reuse the current assembled schema without retaining historical section sets."
  [sections]
  (let [[previous assembled] @*assembled]
    (if (= previous sections)
      assembled
      (let [assembled (reduce-kv
                       (fn [schema path section]
                         (mu/merge schema
                           (reduce (fn [child key] [:map [key {:optional true} child]])
                                   section (reverse path))))
                       [:map] sections)]
        (reset! *assembled [sections assembled])
        assembled))))

(defn removed-sections
  "Only explicit removal releases an instance contract; nil remains state.
   Unmounting leaves cached app-db state and its contract available for reuse."
  [paths before after]
  (let [absent #?(:clj (Object.) :cljs (js-obj))]
    (filterv #(and (not (identical? absent (get-in before % absent)))
                  (identical? absent (get-in after % absent))) paths)))

(defn changed-errors [sections before after]
  (if-not (map? after)
    (validation/explain :map after)
    (let [absent #?(:clj (Object.) :cljs (js-obj))]
    (vec
     (mapcat (fn [[path section]]
               (let [old (get-in before path absent) new (get-in after path absent)]
                 (when (and (not (identical? new absent)) (not (identical? old new)))
                   (map #(update % :path (partial into path)) (validation/explain section new)))))
             sections)))))
