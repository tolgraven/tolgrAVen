(ns tolgraven.modules.search.subs
  (:require
   [tolgraven.react :as r]

   [clojure.string :as string]
   [clojure.walk :as walk]))

(r/reg-sub :search/get
 (fn [db [_ path]]
   (get-in db (into [:search] path))))

(r/reg-sub :search/results
 (fn [db [_ collection]]
   (get-in db [:search :results collection])))

(r/reg-sub :search/get-query
 (fn [db [_ collection]]
   (get-in db [:search :query collection])))

(r/reg-sub :search/previous-query
 (fn [db [_ collection]]
   (get-in db [:search :previous-query collection])))

(r/reg-sub :search/state
 (fn [db [_ path]]
   (get-in db (into [:state :search] path))))

(r/reg-sub :search/open?
 (fn [db [_]]
   (true? (get-in db [:state :search :open?]))))

(r/reg-sub :search/results-open?
 (fn [db [_]]
   (get-in db [:state :search :results-open?])))

(r/reg-sub
 :search/results-for-query
 (fn [[_ collection & [maybe-query]]]
   [(r/subscribe [:search/results collection])
    (r/subscribe [:search/get-query collection])
    (r/subscribe [:search/previous-query collection])])
 (fn [[data new-query last-query] [_ collection & [maybe-query]]]
   (let [query (or maybe-query new-query)]
     (when-not (string/blank? query)
       (get-in data [query]
               (get-in data [last-query])))))) ;show old results until new are ready! doesn't really help when typing rapidly heh

(defn autocomplete-suggestion
  "Build a plain-text completion without treating user input as a regular expression."
  [query {:keys [highlights text_match]}]
  (when-let [snippet (:snippet (first highlights))]
    (let [text (-> snippet
                   (string/replace #"</?mark>" "")
                   (string/replace #"`[^`]*`" "")
                   (string/split #"\n")
                   first)
          index (string/index-of (string/lower-case text) (string/lower-case query))]
      (when (some? index)
        (let [suffix (subs text (+ index (count query)))
              rest (->> (string/split suffix #" ") (take 5) (string/join " "))]
          {:match query :query query :rest rest
           :text (str query rest) :score (or text_match 0)})))))

(r/reg-sub
 :search/autocomplete-for-current-query
 (fn [[_ collection]]
   [(r/subscribe [:search/results-for-query collection])
    (r/subscribe [:search/get-query collection])])
 (fn [[results query] _]
   (when (< 1 (count query))
     (distinct (keep #(autocomplete-suggestion query %) (:hits results))))))

(r/reg-sub
 :search/autocomplete-multi
 (fn [[_ collections]]
   (mapv #(r/subscribe [:search/autocomplete-for-current-query %]) collections))
 (fn [results [_ collections]]
   (let [#_results' #_(for [[result coll] (interleave results collections)]
                   result
                   #_(assoc result :collection coll))]
     (->> (apply concat results)
          ; (filter #(= query (:match %)))
          (sort-by :score >)))))


(r/reg-sub
 :search/snippets-for-query
 (fn [[_ collection & [maybe-query]]]
   (r/subscribe [:search/results-for-query collection maybe-query]))
 (fn [results [_ collection & [maybe-query]]]
   (->> (:hits results)
        (map :highlights)
        flatten
        (map :snippet))))
