(ns tolgraven.modules.blog-editor.model
  "Pure draft attachment transformations. Upload files and status are transient."
  (:require [clojure.string :as string]
            [tolgraven.media.schema :as media]))

(def uploading-statuses #{:queued :uploading :processing})
(def marker-pattern #"<!-- image-upload:[A-Za-z0-9-]+ -->")

(def upload-schema
  [:map
   [:id :string]
   [:name :string]
   [:?owner :string]
   [:?sdk? :boolean]
   [:order [:int {:min 0}]]
   [:placeholder :string]
   [:status [:enum :queued :uploading :processing :ready :failed]]
   [:?file :any]
   [:?job-id :string]
   [:?attempt [:int {:min 1}]]
   [:?polls [:int {:min 0}]]
   [:?error :string]
   [:?asset media/asset]])
(def uploads-schema [:map-of :string upload-schema])

(defn placeholder [id] (str "<!-- image-upload:" id " -->"))

(defn pending? [uploads]
  (boolean (some #(uploading-statuses (:status %)) (vals uploads))))

(defn unresolved? [text uploads]
  (or (pending? uploads) (boolean (re-find marker-pattern (or text "")))))

(defn next-upload [uploads]
  (when-not (some #(#{:uploading :processing} (:status %)) (vals uploads))
    (first (sort-by :order (filter #(= :queued (:status %)) (vals uploads))))))

(defn insert-files [text start end uploads files]
  (let [text' (or text "")
        start' (max 0 (min (count text') start))
        end' (max start' (min (count text') end))
        order (inc (reduce max -1 (map :order (vals uploads))))
        additions (map-indexed
                    (fn [index {:keys [id name] :as file}]
                      (assoc file :order (+ order index)
                                  :placeholder (if (:sdk? file) "" (placeholder id))
                                  :status :queued)) files)
        placeholders (string/join "\n" (remove string/blank? (map :placeholder additions)))]
    {:text (str (subs text' 0 start') placeholders (subs text' end'))
     :uploads (into (or uploads {}) (map (juxt :id identity)) additions)}))

(defn image-markdown [name src]
  (str "![" (-> name
                (string/replace #"\\" "\\\\")
                (string/replace #"\[" "\\[")
                (string/replace #"\]" "\\]")
                (string/replace #"[\r\n]" " "))
       "](" src ")"))

(defn complete-upload [text upload asset]
  ;; A removed placeholder never reappears after a slow completion. Other draft
  ;; edits and attachments retain their position, regardless of completion order.
  (if (:sdk? upload) text
      (string/replace (or text "") (:placeholder upload)
                      (fn [_] (image-markdown (:name upload) (:src asset))))))

(defn cancel-upload [text upload]
  (if (:sdk? upload) text (string/replace (or text "") (:placeholder upload) "")))
