(ns tolgraven.content.contract
  (:require [tolgraven.content.schema :as schema]
            #?(:clj [tolgraven.build.modules :refer [content-definitions]])
            [tolgraven.validation :as validation])
  #?(:cljs (:require-macros [tolgraven.build.modules :refer [content-definitions]])))

(def version 1)
(def module-content (content-definitions))
(def sections (vec (distinct (mapcat val module-content))))

(def module-dependencies
  (into {} (map (fn [[id keys]] [id (if (seq keys) [{:source :strapi :keys keys}] [])]) module-content)))

(def shell-content [:document :header :common :footer :post-footer])
(def route-content
  {:home [:intro :services :moneyshot :story :strava :interlude :soundcloud :gallery]
   :about [:story] :services [:services] :hire [] :cv [:cv] :docs [:docs] :blog [:blog]})

(defn keys-for-route [route]
  (vec (distinct (concat shell-content (filter (set sections) (get route-content route []))))))

(defn validate-keys [requested]
  (let [ks (if (nil? requested) sections (mapv keyword requested))]
    (when-not (and (seq ks) (<= (count ks) (count sections)) (every? (set sections) ks))
      (throw (ex-info "Unknown content section" {:status 400})))
    (vec (distinct ks))))

(defn normalize-content [content]
  ;; Only known semantic values become keywords. Ordinary CMS text stays text;
  ;; the one supported UI action is explicitly mapped, never arbitrary dispatch.
  (cond-> content
    (:header content)
    (update-in [:header :menu] #(into {} (map (fn [[k items]]
                                             [k (mapv (fn [[label url route]] [label url (keyword route)]) items)])) %))
    (:intro content)
    (update-in [:intro :buttons] #(mapv (fn [[label target]]
                                        [label (if (= target ["state" ["contact-form" "show?"] true])
                                                 [:state [:contact-form :show?] true] target)]) %))
    (:blog content) (update-in [:blog :heading :target] #(some-> % keyword))
    (:cv content) (update-in [:cv :heading :target] #(some-> % keyword))
    (:cv content) (update-in [:cv :cv :timeline] #(mapv (fn [item] (update item :category keyword)) %))))

(defn checked-bundle
  "Validate normalized public content once at the transport boundary."
  [bundle requested]
  (let [normalized (update bundle :content normalize-content)]
    (validation/check! :content/bundle schema/bundle normalized)
    (when-not (every? #(contains? (:content normalized) %) requested)
      (throw (ex-info "Content response is missing requested sections" {:status 503})))
    normalized))
