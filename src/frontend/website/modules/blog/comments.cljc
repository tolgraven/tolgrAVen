(ns website.modules.blog.comments
  (:require [website.supabase.query :as query]))

(def page-size 10)

(def comment-id [:and [:string {:min 1, :max 128}] [:re #"^[A-Za-z0-9_-]+$"]])

(defn comment-query [id & [post-id]]
  {:path-collection [:blog-comments]
   :scoped? true
   :reply-counts? true
   :where (cond-> [[:id :== id]] post-id (conj [:parent-post :== post-id]))})

(defn full-path [{:keys [parent-post path id]}]
  (into [parent-post] (concat (map str (rest path)) [(str id)])))

(defn ancestor-paths [row]
  (let [path (full-path row)]
    (mapv #(subvec path 0 %) (range 2 (count path)))))

(defn focus-root [{:keys [focus ancestors standalone-comment?]}]
  (when-let [row (first focus)]
    (if standalone-comment?
      row
      (let [id (second (full-path row))]
        (some #(when (= id (str (:id %))) %) (cons row ancestors))))))

(defn focus-expansion
  ([expanded row] (focus-expansion expanded row true))
  ([expanded row override?]
   (reduce (fn [values path]
             (if (and (not override?) (contains? values path)) values
               (assoc values path true))) expanded (ancestor-paths row))))
(defn expanded?
  "Open two reply levels, or one level below a manually expanded thread.
   An explicit fold always takes precedence, including restored view state."
  ([expanded path] (expanded? expanded path 2))
  ([expanded path root-depth]
   (if-some [value (get expanded path)]
     value
     (or (<= (count path) (inc root-depth))
         (true? (get expanded (pop path)))))))

(defn thread-query [post-id parent-id]
  {:path-collection [:blog-comments] :scoped? true :reply-counts? true
   :where [[:parent-post :== post-id] [:parent-comment :== parent-id]]
   :order-by [[:ts :desc] [:id :desc]] :doc-changes true})
(defn root-query [post-id amount]
  (assoc (thread-query post-id nil) :limit (inc amount)))

(def reveal-plan
  "Acquire two visible reply levels through the same shared query queue before
   child components mount. Explicit descendant folds still stop acquisition."
  [{:id :replies
    :queries (fn [{:keys [path]}] [(thread-query (first path) (last path))])}
   {:id :children :depends [:replies]
    :queries (fn [{:keys [path replies expanded]}]
               (mapv #(thread-query (first path) (:id %))
                     (filter #(and (or (nil? (:reply-count %)) (pos? (:reply-count %)))
                                   (expanded? expanded (conj path (:id %)))) replies)))}
   {:id :authors :depends [:replies :children]
    :queries (fn [{:keys [replies children]}]
               (mapv query/profile-query (sort (distinct (keep :user (concat replies children))))))}])

(defn reveal-duration-ms [count]
  ;; A short thread should finish quickly; large branches get a little more
  ;; room without accumulating a full transition at every nesting level.
  (+ 120 (* 20 (min 8 (max 1 count)))))
