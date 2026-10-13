(ns tolgraven.component.ref
  "Native ref composition. React owns attachment/cleanup; values remain opaque.")

(defn- attach! [reference element]
  (if (fn? reference)
    (let [cleanup! (reference element)]
      (if (fn? cleanup!) cleanup! #(reference nil)))
    (do
      (set! (.-current ^js reference) element)
      #(set! (.-current ^js reference) nil))))

(defn- release-all! [cleanups]
  (let [*error (atom nil)]
    (doseq [cleanup! (reverse cleanups)]
      (try (cleanup!)
           (catch :default error (when-not @*error (reset! *error error)))))
    (when @*error (throw @*error))))

(defn- attach-all! [references element]
  (let [*cleanups (atom [])]
    (try
      (doseq [reference references] (swap! *cleanups conj (attach! reference element)))
      @*cleanups
      (catch :default error
        (try (release-all! @*cleanups) (finally (throw error)))))))

(defn compose
  "Compose callback/object refs without converting their values. A callback's
   returned cleanup runs instead of its legacy nil call, exactly once."
  [& references]
  (let [references' (vec (distinct (remove nil? references)))
        *release (atom nil)]
    (fn [element]
      (when-let [release! @*release] (release!))
      (when element
        (let [cleanups (attach-all! references' element)
              *active? (atom true)]
          (letfn [(release! []
                    (when (compare-and-set! *active? true false)
                      (when (identical? @*release release!) (reset! *release nil))
                      (release-all! cleanups)))]
            (reset! *release release!)
            release!))))))
