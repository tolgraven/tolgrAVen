(ns website.modules.github.patch
  "Unified patch rows retain independent old/new source positions."
  (:require [clojure.string :as string]))

(def row-schema
  [:map
   [:kind [:enum :context :addition :deletion :note]]
   [:text :string]
   [:old :?int]
   [:new :?int]])
(def hunk-schema
  [:map [:header :string] [:rows [:vector row-schema]]])
(def hunks-schema [:vector hunk-schema])

(defn hunks [patch]
  (let [lines (string/split (or patch "") #"\n" -1)
        lines' (if (= "" (last lines)) (butlast lines) lines)]
    (:hunks
      (reduce
        (fn [{:keys [old new] :as state} line]
          (if-let [[_ before after] (re-find #"^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@" line)]
            (assoc (update state :hunks conj {:header line, :rows []})
                   :old (js/parseInt before 10)
                   :new (js/parseInt after 10))
            (if (empty? (:hunks state))
              state
              (let [kind (case (first line) "+" :addition "-" :deletion " " :context :note)
                    old? (#{:context :deletion} kind)
                    new? (#{:context :addition} kind)
                    row {:kind kind
                         :text (if (= :note kind) line (subs line 1))
                         :old (when old? old)
                         :new (when new? new)}]
                (cond-> (update-in state [:hunks (dec (count (:hunks state))) :rows] conj row)
                  old? (update :old inc)
                  new? (update :new inc))))))
        {:hunks [], :old 0, :new 0} lines'))))

(defn language [filename]
  (let [extension (last (string/split (or filename "") #"\."))]
    (get {"clj" "clojure"
          "cljs" "clojure"
          "cljc" "clojure"
          "edn" "clojure"
          "js" "javascript"
          "jsx" "javascript"
          "ts" "typescript"
          "tsx" "typescript"
          "scss" "scss"
          "css" "css"
          "md" "markdown"
          "sh" "bash"
          "yml" "yaml"}
         extension extension)))
