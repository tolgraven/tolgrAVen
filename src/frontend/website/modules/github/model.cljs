(ns website.modules.github.model
  (:require [clojure.string :as string]
            [website.modules.github.message :as message]))

(defn normalize-commit [{:keys [commit sha] :as item}]
  (let [ts (get-in commit [:author :date])
        [date clock] (when ts (string/split ts #"T" 2))]
    (assoc (dissoc item :files)
           :date date
           :clock clock
           :ts ts
           :message (message/parse-message (:message commit))
           :sha7 (when sha (subs sha 0 (min 7 (count sha)))))))

(defn merge-commits
  "Retain one row per SHA when a direct lookup and paginated reads overlap."
  [existing incoming]
  (->> (concat existing incoming)
       (reduce (fn [rows item]
                 (if-let [sha (:sha item)]
                   (update rows sha merge item)
                   rows)) {})
       vals
       (sort-by (juxt #(or (:ts %) (get-in % [:commit :author :date]) "") :sha) #(compare %2 %1))
       vec))

(defn matches? [item query]
  (let [query' (string/lower-case (string/trim (or query "")))
        text (string/lower-case (str (:sha item) " " (get-in item [:commit :message])))]
    (if (re-matches #"[a-f0-9]{7,40}" query')
      (string/starts-with? (string/lower-case (or (:sha item) "")) query')
      (or (string/blank? query') (string/includes? text query')))))

(defn route-target [match]
  (when (= :github (get-in match [:data :component-route :module]))
    (when-let [sha (get-in match [:parameters :path :sha])]
      {:sha (string/lower-case sha), :path (:path match)})))
