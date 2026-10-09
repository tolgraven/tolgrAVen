(ns tolgraven.component.markup
  "Normalize native Hiccup attributes; component specifications stay in CLJS."
  (:require [clojure.string :as string]))

(def spec-keys
  #{:props :classes :features :depends :appear :seen :on-seen :exit :presence
    :loading :loading-prefab :loading-tag :loading-props :loading-args :skeleton
    :page :module :spec :profile :capture-root? :init :state})

(defn attrs [tag value]
  (let [base (apply dissoc value spec-keys)
        props (apply dissoc (:props value) spec-keys)
        loading (get (:props value) :loading (:loading value))
        image-or-frame? (contains? #{"img" "iframe"} (re-find #"^[^.#]+" (name tag)))
        classes (remove nil? [(:class base) (:class props) (:classes value)])]
    (cond-> (merge base props)
      (and image-or-frame? (contains? #{"lazy" "eager"} loading)) (assoc :loading loading)
      (seq classes) (assoc :class (string/join " " (map #(if (sequential? %) (string/join " " %) %) classes)))
      (or (:style base) (:style props)) (assoc :style (merge (:style base) (:style props))))))

(defn native? [tag]
  (or (string? tag) (and (keyword? tag) (not (#{:<> :> :r> :f>} tag)))))

(defn normalize-form
  "Flatten :props on native elements, retaining component args and React keys."
  [form]
  (cond
    (and (vector? form) (or (native? (first form)) (= :<> (first form))))
    (let [[tag maybe-attrs & children] form
          attrs? (map? maybe-attrs)]
      (with-meta
        (into (if attrs? [tag (attrs tag maybe-attrs)] [tag])
              (map normalize-form (if attrs? children (rest form))))
        (meta form)))
    (and (sequential? form) (not (vector? form))) (map normalize-form form)
    :else form))
