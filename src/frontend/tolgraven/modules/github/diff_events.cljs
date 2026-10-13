(ns tolgraven.modules.github.diff-events
  "GitHub reads use the shared HTTP effects; all request states remain inspectable."
  (:require [tolgraven.react :as rf]
            [ajax.core :as ajax]
            [tolgraven.modules.github.diff :as diff]))

(defn state-path [revision & keys] (into [:state :github :diff-view (diff/key-for revision)] keys))
(defn content-path [revision & keys] (into [:content :github :diff-view (diff/key-for revision)] keys))
(defn http-read [uri accept success failure]
  [:http/get {:uri uri
              :background? true
              :timeout 20000
              :headers {"Accept" accept, "X-GitHub-Api-Version" "2022-11-28"}}
   success failure])

(rf/reg-event-fx :github/diff-open
  (fn [{:keys [db]} [_ revision]]
    (when (and (diff/valid-revision? revision)
               (not (#{:loading :ready} (get-in db (state-path revision :metadata :status)))))
      {:db (assoc-in db (state-path revision) {:mode :diff, :selected "", :metadata {:status :loading}})
       :dispatch (http-read (diff/metadata-url revision 1) "application/vnd.github+json"
                            [:github/diff-metadata revision 1]
                            [:github/diff-failed revision [:metadata]])})))

(rf/reg-event-fx :github/diff-metadata
  (fn [{:keys [db]} [_ revision page commit]]
    (let [previous (get-in db (content-path revision :commit))
          commit' (if (= page 1) commit (assoc commit :files (into (vec (:files previous)) (:files commit))))
          more? (and (= 100 (count (:files commit))) (< page 30))
          selected (or (get-in db (state-path revision :selected)) "")
          selected' (if (seq selected) selected (or (:filename (first (:files commit'))) ""))]
      {:db (-> db
               (assoc-in (content-path revision :commit) commit')
               (assoc-in (state-path revision :selected) selected')
               (assoc-in (state-path revision :metadata) {:status (if more? :loading :ready)}))
       :dispatch (if more?
                   (http-read (diff/metadata-url revision (inc page)) "application/vnd.github+json"
                              [:github/diff-metadata revision (inc page)]
                              [:github/diff-failed revision [:metadata]])
                   [:github/diff-ensure revision])})))

(rf/reg-event-db :github/diff-failed
  (fn [db [_ revision path failure]]
    (assoc-in db (apply state-path revision path)
              {:status :error
               :message (if (= 403 (:status failure))
                          "GitHub denied this request or its public API rate limit was reached."
                          "GitHub could not load this revision. Retry or open the commit on GitHub.")})))

(rf/reg-event-fx :github/diff-mode
  (fn [{:keys [db]} [_ revision mode]]
    (when (#{:diff :patch} mode)
      {:db (assoc-in db (state-path revision :mode) mode)
       :dispatch [:github/diff-ensure revision]})))
(rf/reg-event-fx :github/diff-select
  (fn [{:keys [db]} [_ revision filename]]
    (when (some #(= filename (:filename %)) (get-in db (content-path revision :commit :files)))
      {:db (assoc-in db (state-path revision :selected) filename)
       :dispatch [:github/diff-ensure revision]})))

(rf/reg-event-fx :github/diff-ensure
  (fn [{:keys [db]} [_ revision]]
    (let [{:keys [mode selected]} (get-in db (state-path revision))
          commit (get-in db (content-path revision :commit))
          file (first (filter #(= selected (:filename %)) (:files commit)))
          path (if (= :patch mode) [:patch] [:files selected])
          status (get-in db (apply state-path revision (conj path :status)))
          sides (when file (diff/sides commit file))]
      (when (and commit (not= :loading status))
        (cond
          (= :ready status) {:db (assoc-in db (state-path revision :displayed) {:mode mode, :selected selected})}
          (= :patch mode)
          {:db (assoc-in db (state-path revision :patch) {:status :loading})
           :dispatch [:http/get {:uri (diff/commit-url revision)
                                 :background? true
                                 :timeout 20000
                                 :headers {"Accept" "application/vnd.github.patch"}
                                 :response-format (ajax/text-response-format)}
                      [:github/diff-patch revision]
                      [:github/diff-failed revision [:patch]]]}
          (nil? file) nil
          (not (every? #(and (diff/safe-path? (:path %))
                            (re-matches #"[0-9a-fA-F]{40}" (or (:sha %) ""))) (keep val sides)))
          {:db (assoc-in db (state-path revision :files selected)
                        {:status :error, :message "This file has an unsupported repository path or revision."})}
          :else
          {:db (-> db
                   (assoc-in (state-path revision :files selected) {:status :loading})
                   (assoc-in (content-path revision :files selected) {}))
           :dispatch-n (mapv (fn [[side spec]]
                               (if spec
                                 (http-read (diff/contents-url revision spec) "application/vnd.github+json"
                                            [:github/diff-source revision selected side]
                                            [:github/diff-failed revision [:files selected]])
                                 [:github/diff-source revision selected side
                                  {:type "file", :encoding "base64", :content "", :size 0}])) sides)})))))

(rf/reg-event-db :github/diff-patch
  (fn [db [_ revision text]]
    (if (and (string? text) (<= (.-length (.encode (js/TextEncoder.) text)) diff/max-bytes))
      (-> db
          (assoc-in (content-path revision :patch) text)
          (assoc-in (state-path revision :patch) {:status :ready})
          (cond-> (= :patch (get-in db (state-path revision :mode)))
            (assoc-in (state-path revision :displayed) {:mode :patch, :selected ""})))
      (assoc-in db (state-path revision :patch)
                {:status :error, :message "This patch exceeds the 1 MiB viewer limit. Open it on GitHub."}))))

(rf/reg-event-db :github/diff-source
  (fn [db [_ revision filename side response]]
    (if (= :error (get-in db (state-path revision :files filename :status)))
      db
      (try
        (let [text (diff/decode-text response)
              db' (assoc-in db (content-path revision :files filename side) text)
              loaded (get-in db' (content-path revision :files filename))]
          (if (and (contains? loaded :before) (contains? loaded :after))
            (cond-> (assoc-in db' (state-path revision :files filename) {:status :ready})
              (and (= :diff (get-in db (state-path revision :mode)))
                   (= filename (get-in db (state-path revision :selected))))
              (assoc-in (state-path revision :displayed) {:mode :diff, :selected filename})) db'))
        (catch :default error
          (assoc-in db (state-path revision :files filename) {:status :error, :message (.-message error)}))))))
