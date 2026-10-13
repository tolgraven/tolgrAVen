(ns tolgraven.modules.blog-editor.anchors
  "Pending insertion points belong to the upload queue, never the Markdown text.")

(def positions-schema [:map-of :string [:int {:min 0}]])
(def change-schema
  [:map [:from [:int {:min 0}]] [:to [:int {:min 0}]] [:inserted :string]])

(defn common-prefix-length [before after]
  (loop [index 0]
    (if (and (< index (min (count before) (count after)))
             (= (nth before index) (nth after index)))
      (recur (inc index))
      index)))

(defn text-change
  "External edits use one bounded replacement; native source transactions supply exact changes."
  [before after]
  (let [before' (or before "")
        after' (or after "")
        start (common-prefix-length before' after')
        tail (loop [length 0]
               (if (and (< length (- (count before') start))
                        (< length (- (count after') start))
                        (= (nth before' (- (count before') length 1))
                           (nth after' (- (count after') length 1))))
                 (recur (inc length))
                 length))]
    {:from start
     :to (- (count before') tail)
     :inserted (subs after' start (- (count after') tail))}))

(defn map-position
  "Map an insertion point through ordered original-document changes. Deleting across
   a chip removes its anchor; typing at its right-hand caret keeps it before that text."
  [position changes]
  (loop [remaining (seq changes), delta 0]
    (if-let [{:keys [from to inserted]} (first remaining)]
      (cond
        (< position from) (+ position delta)
        (and (< from position) (< position to)) nil
        (= position from) (+ from delta)
        (= position to) (+ from delta (count inserted))
        :else (recur (next remaining) (+ delta (- (count inserted) (- to from)))))
      (+ position delta))))

(defn map-positions [positions changes]
  (into {} (keep (fn [[id position]]
                   (when-let [position' (map-position position changes)] [id position']))) positions))

(defn insert-image [text position markdown]
  (let [position' (max 0 (min (count text) position))]
    (str (subs text 0 position') markdown (subs text position'))))
