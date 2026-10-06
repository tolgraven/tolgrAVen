(ns tolgraven.dev-console.layout
  "Pure, bounded pagination and layout timeline projections.")

(def page-size 10)
(defn page
  ([items requested] (page items requested (when (counted? items) (count items))))
  ([items requested total]
   (let [index (max 0 (if total (min requested (max 0 (dec (int (js/Math.ceil (/ total page-size)))))) requested))
         offset (* index page-size)
         window (vec (take (inc page-size) (drop offset items)))]
     {:index index :offset offset :total total :items (vec (take page-size window))
      :more? (> (count window) page-size)})))

(defn nearby [records shift]
  ;; Temporal neighbours are evidence for investigation, not proven causes.
  (->> records
       (filter #(and (#{:epoch :render} (:kind %))
                     (number? (:start %))
                     (<= 0 (- (:start shift) (or (:commit %) (:start %))) 1000)))
       (sort-by #(or (:commit %) (:start %)))
       (take-last 8) vec))

(defn timeline [records window-ms delayed-only?]
  (let [shifts (filter #(and (= :layout (:kind %)) (number? (:start %))) records)
        latest (reduce max 0 (filter number? (map :start records)))
        start (if window-ms (max 0 (- latest window-ms))
                  (reduce min latest (map :start shifts)))
        end (max (inc start) latest)
        shifts (vec (filter #(and (<= start (:start %) end)
                                  (or (not delayed-only?) (not (:user-input? %)))) shifts))]
    {:start start :end end :span (- end start) :shifts shifts
     :peak (reduce max 0.000001 (keep :value shifts))
     :total (reduce + 0 (keep :value shifts))
     :delayed (count (remove :user-input? shifts))
     :context (vec (take-last 80 (filter #(and (#{:epoch :render} (:kind %))
                                               (number? (:start %)) (<= start (:start %) end)) records)))}))

(defn record-key [record]
  (case (:kind record)
    :epoch [:event (first (:event record))]
    :trace [:trace (:op-type record)
            (or (first (get-in record [:tags :event]))
                (let [operation (:operation record)]
                  (if (and (vector? operation) (keyword? (first operation)))
                    (first operation) operation)))]
    :render [:render (:component record) (:phase record)]
    [(:kind record) (:operation record)]))

(defn group-records
  "Presentation only. Preserve every bounded occurrence and argument for drilldown."
  [records]
  (->> records
       (group-by record-key)
       (map (fn [[id occurrences]]
              (let [timed (filter #(number? (:start %)) occurrences)
                    latest (last (sort-by #(or (:start %) 0) occurrences))]
                {:id id :count (count occurrences)
                 :start (when (seq timed) (reduce min (map :start timed)))
                 :end (when (seq timed) (reduce max (map #(or (:end %) (:start %)) timed)))
                 :duration (reduce + 0 (filter number? (map :duration occurrences)))
                 :latest-args (or (some-> latest :event rest vec)
                                  (some-> latest :tags :event rest vec))
                 :occurrences (vec occurrences)})))
       (sort-by #(or (:end %) 0) >) vec))
