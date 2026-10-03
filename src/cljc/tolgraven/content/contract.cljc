(ns tolgraven.content.contract)

(def version 1)
(def sections [:document :header :intro :services :moneyshot :story :strava :interlude
               :cv :soundcloud :gallery :blog :docs :common :footer :post-footer])

;; Available to the backend before any JavaScript module is loaded. Module specs
;; reference this same manifest, so SSR and browser initialization cannot drift.
(def module-content
  {:blog [:blog :common] :cv [:cv] :docs [:docs] :strava [:strava]
   :user [:common] :instagram [] :chat [] :github [] :gpt []
   :search [] :link-preview [] :test []})
(def shell-content [:document :header :common :footer :post-footer])
(def route-content
  {:home [:intro :services :moneyshot :story :strava :interlude :soundcloud :gallery]
   :about [:story] :services [:services] :hire [] :cv [:cv] :docs [:docs] :blog [:blog]})

(defn keys-for-route [route]
  (vec (distinct (concat shell-content (get route-content route [])))))

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
