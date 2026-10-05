(ns tolgraven.component.instrumentation
  "Development-only React profiling. Adds no DOM wrappers or application store."
  (:require [clojure.string :as string]
            [reagent.core :as r]
            [tolgraven.react :as rf]
            [tolgraven.render-context :as context]
            [tolgraven.dev-console.capture :as capture]))

(defonce *path-resolver (atom nil))
(defonce *dependencies-resolver (atom nil))
(defn register-dependencies-resolver! [resolve!] (reset! *dependencies-resolver resolve!))
(defn register-path-resolver! [resolve!] (reset! *path-resolver resolve!))

(defn identity-for [definition] [(:ns definition) (:name definition)])
(r/defc <probe> [definition args instance-key form]
  (let [*id (rf/use-ref nil)
        *details (rf/use-ref nil)
        _ (when-not (.-current *id) (set! (.-current *id) (str (random-uuid))))
        qualified (string/join "/" (identity-for definition))
        path (when-let [resolve! @*path-resolver] (resolve! definition args instance-key))
        connected? @capture/*connected?
        resolve-dependencies! (fn []
                                (when-let [resolve! @*dependencies-resolver]
                                  (try (capture/preview (resolve! definition args))
                                       (catch :default error [{:resolution-error (str error)}]))))
        dependencies (when connected? (resolve-dependencies!))]
    ;; Keep only the mounted instance's current resolver while closed. Opening
    ;; the inspector can backfill details without computing them on every render.
    (set! (.-current *details) (fn [] {:depends (if connected? dependencies (resolve-dependencies!))}))
    (rf/use-effect
      (fn []
        (do (capture/emit! {:kind :mount :instance (.-current *id) :component (identity-for definition)
                          :path path :resolve! #((.-current *details)) :key instance-key :page (when (exists? js/location) (.-pathname js/location))})
          #(capture/emit! {:kind :unmount :instance (.-current *id)})))
      #js [qualified (pr-str [path dependencies])])
    [:> rf/profiler
     {:id qualified
      :onRender (when connected? (fn [_ phase duration base start commit]
                  (when @capture/*recording?
                    (capture/emit! {:kind :render :component (identity-for definition)
                                    :instance (.-current *id) :phase phase :duration duration
                                    :base-duration base :start start :commit commit :end (+ start duration)}))))}
     form]))

(defn instrument
  ([definition form]
   (let [argv (rf/component-argv)]
     (instrument definition form (vec (rest argv)) (:key (meta argv)))))
  ([definition form args key]
   (if (or (not ^boolean goog.DEBUG)
           (= false (get-in definition [:options :profile]))
           (= (:ns definition) "tolgraven.dev-console.views"))
     form
     (if (fn? form)
       (fn [& args] (instrument definition (apply form args) args key))
       (let [qualified (string/join "/" (identity-for definition))
             native? (and (vector? form) (or (string? (first form))
                                            (and (keyword? (first form)) (not (#{:<> :> :r> :f>} (first form))))))
             ;; The server loader and browser loader legitimately have different
             ;; adapter identities. Attribute only after the hydration commit.
             form (if (and native? (not context/*server?*) @context/*interactive?)
                    (let [[tag attrs & children] form]
                      (with-meta (into [tag (assoc (if (map? attrs) attrs {}) :data-dev-component qualified)]
                                       (if (map? attrs) children (rest form))) (meta form))) form)]
         (if context/*server?* form
             [<probe> definition args key form]))))))
