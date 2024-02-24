(ns tolgraven.search.subs
  (:require
   [re-frame.core :as rf]
   [tolgraven.ui :as ui]
   [clojure.string :as string]
   [clojure.walk :as walk]))

(rf/reg-sub :search/get
 (fn [db [_ path]]
   (get-in db (into [:search] path))))

(rf/reg-sub :search/results
 (fn [db [_ collection]]
   (get-in db [:search :results collection])))

(rf/reg-sub :search/get-query
 (fn [db [_ collection]]
   (get-in db [:search :query collection])))

(rf/reg-sub :search/previous-query
 (fn [db [_ collection]]
   (get-in db [:search :previous-query collection])))

(rf/reg-sub :search/state
 (fn [db [_ path]]
   (get-in db (into [:state :search] path))))

(rf/reg-sub :search/open?
 (fn [db [_ path]]
   (get-in db [:state :search :open?])))

(rf/reg-sub :search/results-open?
 (fn [db [_ path]]
   (get-in db [:state :search :results-open?])))

(rf/reg-sub
 :search/results-for-query
 (fn [[_ collection & [maybe-query]]]
   [(rf/subscribe [:search/results collection])
    (rf/subscribe [:search/get-query collection])
    (rf/subscribe [:search/previous-query collection])])
 (fn [[data new-query last-query] [_ collection & [maybe-query]]]
   (let [query (or maybe-query new-query)]
     (when-not (string/blank? query)
       (get-in data [query]
               (get-in data [last-query])))))) ;show old results until new are ready! doesn't really help when typing rapidly heh

(rf/reg-sub
 :search/autocomplete-for-current-query
 (fn [[_ collection]]
   [(rf/subscribe [:search/results-for-query collection])
    (rf/subscribe [:search/get-query collection])])
 (fn [[results query] [_ collection]]
  (when (and (seq results)
             (< 1 (count query)))
   (distinct
    (for [hit (:hits results)
          :let [{:keys [highlights document text_match]} hit
                {:keys [id permalink title text user ts]} document
                {:keys [snippet matched_tokens field]} (first highlights)]]
      (let [token (first matched_tokens)
            snippet' (-> snippet
                         (string/replace-first (str ".*" query) "")
                         (string/replace #"(```)|(`.*)" "") ; kill the code blocks
                         ; (string/replace #"\n.*" "") ; kill the code blocks
                         (string/replace #"(<mark>)|(</mark>)" "")
                         ; (string/replace #"([^a-zA-Z0-9\s'])|(\..*)" "")
                         #_(string/replace #"<mark>" "öööö")
                         #_(string/replace #"</mark>" "åååå"))
            data (-> (string/split snippet' (re-pattern (str "(" query ")|(" token ")")))
                     last ; here is where we might end up all wrong
                     (string/split #" ")
                     (->>
                      (take 5) ; thinking it'll be cached and not change but maybe not
                      (string/join " ")))
            text (str (-> data
                          #_(string/replace #"öööö|åååå" "")))
            trimmed (-> (str "<mark>" token query)
                        ; (string/replace #"öööö" "<mark>")
                        ; (string/replace #"åååå" "</mark>")
                        (string/replace #"([^a-zA-Z0-9\s'])|(\.*)" ""))]
        
        {:html trimmed
         :match token
         :query query
         :rest text
         :text (str query text)
         ; :text (str query (string/replace (str token text) (re-pattern query) ""))
         :score text_match}))))))

(rf/reg-sub
 :search/autocomplete-multi
 (fn [[_ collections]]
   (mapv #(rf/subscribe [:search/autocomplete-for-current-query %]) collections))
 (fn [results [_ collections]]
   (let [#_results' #_(for [[result coll] (interleave results collections)]
                   result
                   #_(assoc result :collection coll))]
     (->> (apply concat results)
          ; (filter #(= query (:match %)))
          (sort-by :score >)))))


(rf/reg-sub
 :search/snippets-for-query
 (fn [[_ collection & [maybe-query]]]
   (rf/subscribe [:search/results-for-query collection maybe-query]))
 (fn [results [_ collection & [maybe-query]]]
   (->> (:hits results)
        (map :highlights)
        flatten
        (map :snippet))))

