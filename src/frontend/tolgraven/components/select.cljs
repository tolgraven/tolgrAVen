(ns tolgraven.components.select
  "Searchable native-value selection, with transient filter/focus owned by the view."
  (:require [clojure.string :as string]
            [tolgraven.react :as r]
            [tolgraven.macros :refer-macros [defc]]))

(def option-schema [:map [:value :string] [:label :string]])
(defn matches? [query {:keys [value label]}]
  (string/includes? (string/lower-case (str label " " value)) (string/lower-case query)))

(defc <search-select>
  "Filter options by label or value; arrows and Enter select, Escape closes."
  {:spec-schema [:map [:value :string] [:options [:vector option-schema]] [:on-change fn?]
                      [:label :string] [:?allow-custom? :boolean]]}
  [{:keys [value options on-change label allow-custom?]}]
  :let [*open? (r/atom false)
        *query (r/atom "")
        *index (r/atom 0)]
  (let [id (r/use-id)
        selected (some #(when (= value (:value %)) %) options)
        matches (filterv (partial matches? @*query) options)
        choices (cond-> matches
                  (and allow-custom? (seq @*query) (not (some #(= @*query (:value %)) options)))
                  (conj {:value @*query, :label (str "Use “" @*query "”")}))
        choose! (fn [choice] (on-change (:value choice)) (reset! *open? false) (reset! *query ""))]
    [:div.search-select
     [:input {:type "search"
              :role "combobox"
              :aria-label label
              :aria-expanded @*open?
              :aria-autocomplete "list"
              :aria-controls id
              :aria-activedescendant (when (and @*open? (seq choices)) (str id "-" (min @*index (dec (count choices)))))
              :value (if @*open? @*query (or (:label selected) value))
              :on-focus #(do (reset! *query "") (reset! *index 0) (reset! *open? true))
              :on-blur #(reset! *open? false)
              :on-change #(do (reset! *query (.. % -target -value)) (reset! *index 0) (reset! *open? true))
              :on-key-down (fn [event]
                             (case (.-key event)
                               "Escape" (do (.preventDefault event) (reset! *open? false))
                               "ArrowDown" (do (.preventDefault event) (reset! *open? true)
                                               (swap! *index #(min (inc %) (max 0 (dec (count choices))))))
                               "ArrowUp" (do (.preventDefault event) (swap! *index #(max 0 (dec %))))
                               "Enter" (when-let [choice (get choices (min @*index (dec (count choices))))]
                                         (.preventDefault event) (choose! choice))
                               nil))}]
     (when @*open?
       [:div.search-select__options {:id id, :role "listbox", :aria-label label}
        (for [[index choice] (map-indexed vector choices)]
          ^{:key (:value choice)}
          [:button {:id (str id "-" index)
                    :type "button"
                    :role "option"
                    :aria-selected (= value (:value choice))
                    ;; Retain input focus while choosing with the pointer.
                    :on-pointer-down #(.preventDefault %)
                    :on-click #(choose! choice)} (:label choice)])
        (when (empty? choices) [:p "No matching options"])])]))
