(ns tolgraven.blog.ssr-client
  (:require [react :as react]
            [reagent.core :as r]
            [re-frame.core-instrumented :as rf]
            [tolgraven.blog.ssr-view :as view]
            [tolgraven.supabase.scoped :as scoped]
            [tolgraven.loader :as loader]
            [tolgraven.service-status :as status]))

(defonce *snapshot (r/atom nil))

(defn leave! [path]
  (when (and @*snapshot (not= path (:path @*snapshot))) (reset! *snapshot nil)))

(defn install! []
  (when-let [element (.getElementById js/document "blog-ssr-bootstrap")]
    (let [snapshot (js->clj (js/JSON.parse (.-textContent element)) :keywordize-keys true)]
      (when-not (and (= 1 (:renderer-version snapshot))
                     (= (.-pathname js/location) (:path snapshot))
                     (vector? (:posts snapshot)))
        (throw (js/Error. "Invalid blog hydration snapshot")))
      (reset! *snapshot snapshot)
      ;; Exact query cache only: a single post never marks a whole table loaded.
      (doseq [post (:posts snapshot)]
        (let [opts {:path-collection [:blog-posts] :scoped? true
                    :where [[:id :== (:id post)]] :doc-changes true}]
          (rf/dispatch-sync [:store/scoped (scoped/query-key opts)
                             {:docs [{:id (str (:id post))
                                      :data (dissoc post :author :date)}]}])))
      snapshot)))

(defn active? []
  (when @*snapshot
    ;; Subscribe so navigation invalidates the choice of root, but never replace
    ;; a successfully hydrated article merely because initialization completes.
    (let [route @(rf/subscribe [:common/route])]
      (and (= (:path @*snapshot) (.-pathname js/location))
           (or (nil? (:path route)) (= (:path @*snapshot) (:path route)))))))

(r/defc <page> []
  (let [[ready? set-ready!] (react/useState false)
        [comments set-comments!] (react/useState nil)]
    (react/useEffect
     (fn []
       (let [*active? (atom true)]
         (set-ready! true)
         (-> (loader/load! {:module :blog :view :comments})
             (.then (fn [spec]
                      (when @*active? (set-comments! (fn [] (get-in spec [:view :comments]))))))
             (.catch (fn [_]
                       (when @*active?
                         (status/fail! :blog-comments "Comments unavailable"
                                       "Comments could not initialize. The article is still available."
                                       #(.reload js/location))))))
         #(reset! *active? false))) #js [])
    [view/<page> @*snapshot comments (when ready? status/<notices>)]))
