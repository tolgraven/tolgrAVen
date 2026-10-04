(ns tolgraven.ssr.client
  (:require [react :as react]
            [reagent.core :as r]
            [re-frame.core-instrumented :as rf]
            [tolgraven.ssr.views :as view]
            [tolgraven.ssr.contract :as contract]
            [tolgraven.component :as component]
            [tolgraven.supabase.scoped :as scoped]
            [tolgraven.loader :as loader]
            [tolgraven.service-status :as status]))

(defonce *snapshot (r/atom nil))

(defn leave! [path]
  (when (and @*snapshot (not= path (:path @*snapshot)))
    (if (and (= "landing" (name (:kind @*snapshot))) (contract/page-spec path))
      (swap! *snapshot assoc :path path)
      (reset! *snapshot nil))))

(defn install! []
  (when-let [element (.getElementById js/document "ssr-bootstrap")]
    (let [snapshot (js->clj (js/JSON.parse (.-textContent element)) :keywordize-keys true)]
      (when-not (and (= 2 (:renderer-version snapshot))
                     (= (.-pathname js/location) (:path snapshot))
                     (vector? (:posts snapshot)))
        (throw (js/Error. "Invalid page hydration snapshot")))
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

(r/defc <island> [module component]
  (let [[near? set-near!] (react/useState false)
        element (react/useRef nil)]
    (react/useEffect
     (fn []
       (if (exists? js/IntersectionObserver)
         (let [observer (js/IntersectionObserver.
                          (fn [entries observer]
                            (when (some #(.-isIntersecting %) (array-seq entries))
                              (.disconnect observer) (set-near! true)))
                          #js {:rootMargin "800px"})]
           (.observe observer (.-current element))
           #(.disconnect observer))
         (do (set-near! true) js/undefined))) #js [])
    [:div {:ref element}
     (if near? [component module]
       [:p {:role "status"} (str (name module) " loads when you approach this section.")])]))

(r/defc <page> [enhancements]
  (let [[ready? set-ready!] (react/useState false)
        [comments set-comments!] (react/useState nil)
        island (react/useMemo (fn [] (fn [id] [<island> id (:module enhancements)]))
                              #js [(:module enhancements)])]
    (react/useEffect
     (fn []
       (let [*active? (atom true)]
         (set-ready! true)
         (when (= "blog" (name (or (:kind @*snapshot) :blog)))
          (-> (loader/load! {:module :blog :view :comments})
             (.then (fn [spec]
                      (when @*active? (set-comments! (fn [] (component/resolve-view (get-in spec [:view :comments])))))))
             (.catch (fn [_]
                       (when @*active?
                         (status/fail! :blog-comments "Comments unavailable"
                                       "Comments could not initialize. The article is still available."
                                       #(.reload js/location)))))))
         #(reset! *active? false))) #js [])
    [view/<page> @*snapshot
     (when ready?
       (assoc enhancements :comments comments :notices status/<notices>
               :island island))]))
