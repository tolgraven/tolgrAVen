(ns tolgraven.schema.app-db
  "Partial sections assemble without closing the rest of app-db. Owners can add
   sections; the event adapter checks only changed section references."
  (:require [malli.util :as mu]
            [tolgraven.blog.schema :as blog]
            [tolgraven.validation :as validation]))

(def sections
  (merge blog/sections
    {[:state] [:map [:menu {:optional true} :boolean]
                   [:is-personal {:optional true} :boolean]
                   [:theme-force-dark {:optional true} :boolean]]
     [:options] [:map [:auto-save-vars {:optional true} :boolean]
                      [:hud {:optional true} [:map [:timeout {:optional true} [:and number? [:>= 0]]]
                                                   [:level {:optional true} :keyword]]]]
     [:content] :map
     [:component] :map
     [:page] :map
     [:module] :map
     [:global] :map
     [:loader] [:map [:code-ready {:optional true} [:map-of :keyword :boolean]]
                      [:errors {:optional true} :map]]}))

(def schema
  "Compile one assembled schema per section set, reused by repeated renders."
  (memoize
   (fn [sections]
    (reduce-kv (fn [schema path section]
               (mu/merge schema
                 (reduce (fn [child key] [:map [key {:optional true} child]])
                         section (reverse path))))
             [:map] sections))))

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
