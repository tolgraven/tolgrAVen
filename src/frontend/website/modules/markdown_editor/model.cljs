(ns website.modules.markdown-editor.model
  "Pure draft attachment transformations. Upload files and status are transient."
  (:require [clojure.string :as string]
            [website.components.content.upload-schema :as upload-schema]
            [website.modules.markdown-editor.anchors :as anchors]
            [website.media.schema :as media]))

(def uploading-statuses #{:queued :uploading :processing})

(def progress-schema upload-schema/progress)

(def upload-schema
  [:map
   [:id :string]
   [:name :string]
   [:?owner :string]
   [:?sdk? :boolean]
   [:?form-path [:vector :any]]
   [:?kind [:enum :post :comment]]
   [:?anchor-attached? :boolean]
   [:?cancelled? :boolean]
   [:order [:int {:min 0}]]
   [:position [:int {:min 0}]]
   [:?chip-dismissed? :boolean]
   [:?image-inserted? :boolean]
   [:?image-inserting? :boolean]
   [:status [:enum :queued :uploading :processing :ready :failed]]
   [:?file :any]
   [:?job-id :string]
   [:?attempt [:int {:min 1}]]
   [:?polls [:int {:min 0}]]
   [:?error :string]
   [:?progress progress-schema]
   [:?preview-ready? :boolean]
   [:?preview-error :string]
   [:?preview-attempt [:int {:min 0}]]
   [:?asset media/asset]])
(def uploads-schema [:map-of :string upload-schema])

(def editor-schema
  [:map
   [:form-path [:vector :any]]
   [:kind [:enum :post :comment]]
   [:?document-id :?string]])

(defn preview-generations
  "A new upload of an already shown source still requires its own visible acknowledgment."
  [uploads]
  (into {} (map (fn [[src items]]
                  [src (mapv (fn [{:keys [id attempt preview-attempt]}]
                               [id attempt (or preview-attempt 0)])
                             (sort-by :id items))]))
        (group-by #(get-in % [:asset :src]) uploads)))

(defn pending? [uploads]
  (boolean (some #(uploading-statuses (:status %)) (vals uploads))))

(defn unresolved? [_ uploads]
  (boolean (some #(or (not= :ready (:status %)) (not (:image-inserted? %))) (remove :cancelled? (vals uploads)))))

(defn positions [uploads]
  (into {} (map (juxt :id :position))
        (remove :chip-dismissed? (vals uploads))))

(defn map-uploads [uploads changes]
  (into {} (keep (fn [[id upload]]
                   (when-let [position (anchors/map-position (:position upload) changes)]
                     [id (assoc upload :position position)]))) uploads))

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
                                  :position start'
                                  :status :queued)) files)
        changes [{:from start', :to end', :inserted ""}]]
    {:text (str (subs text' 0 start') (subs text' end'))
     :uploads (into (map-uploads (or uploads {}) changes) (map (juxt :id identity)) additions)}))

(defn image-markdown [name src]
  (str "![" (-> name
                (string/replace #"\\" "\\\\")
                (string/replace #"\[" "\\[")
                (string/replace #"\]" "\\]")
                (string/replace #"[\r\n]" " "))
       "](" src ")"))

(defn complete-upload [text upload asset]
  (if (:sdk? upload) text
      (anchors/insert-image (or text "") (:position upload) (image-markdown (:name upload) (:src asset)))))

(defn cancel-upload [text _] text)
