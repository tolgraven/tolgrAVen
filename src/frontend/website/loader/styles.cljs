(ns website.loader.styles
  "React owns stylesheet insertion; this adapter observes load/error and retries."
  (:require [website.react :as r]
            [website.render-context :as context]
            [website.loader.style-catalog :as catalog]
            [website.browser-resources.page :as page]
            [website.validation.runtime :as validation]))

(def manifest-schema [:map-of :keyword [:vector :string]])
(defonce *requests (atom {}))
(defonce *attempts (atom {}))
(defonce *ready (atom #{}))
(defonce ^:private *manifests (js/WeakMap.))
(def fallback-manifest
  (into {} (map (fn [[id spec]] [id (:paths spec)])) catalog/modules))

(defn manifest []
  (if-let [element (when (exists? js/document)
                    (.getElementById js/document "module-styles"))]
    (let [text (.-textContent element)
          cached (.get *manifests element)]
      (if (= text (:text cached))
        (:value cached)
        (let [value (js->clj (js/JSON.parse text) :keywordize-keys true)]
          (validation/check! "module stylesheet manifest" manifest-schema value)
          (.set *manifests element {:text text, :value value})
          value)))
    fallback-manifest))

(defn- link [href]
  (let [absolute (.-href (js/URL. href (.-baseURI js/document)))]
    (some #(when (= absolute (.-href %)) %)
          (array-seq (.querySelectorAll js/document "link[rel=stylesheet]")))))

(defn- inline-style [href]
  (let [absolute #(.-href (js/URL. % (.-baseURI js/document)))
        href (absolute href)]
    (some #(when (= href (absolute (.getAttribute % "data-module-style"))) %)
          (array-seq (.querySelectorAll js/document "style[data-module-style]")))))

(defn required-paths [module]
  ;; A dependency's popup sheet belongs to that popup's activation. Its code
  ;; dependency alone must not make ordinary route acquisition download it.
  (let [inventory (manifest)]
    (vec (distinct (concat (catalog/initial-paths inventory module) (get inventory module))))))

(defn ready? [module]
  (or context/*server?*
      (every? #(or (contains? @*ready %)
                  (when (or (some-> (inline-style %) .-sheet)
                            (some-> (link %) .-sheet))
                    ;; React keeps installed resources for this document. Once
                    ;; observed, readiness needs no repeated DOM/URL scan.
                    (swap! *ready conj %)
                    true))
              (required-paths module))))

(defn acquire-path! [path]
  (or (get @*requests path)
      (when (some-> (inline-style path) .-sheet)
        (let [ready (js/Promise.resolve nil)]
          (swap! *ready conj path)
          (swap! *requests assoc path ready)
          ready))
      (let [attempt (get @*attempts path 0)
            ;; React deduplicates resources by href, including failed resources.
            ;; A fresh retry URL lets React retry without mutating its DOM nodes.
            href (str path (when (pos? attempt)
                             (str (if (.includes path "?") "&" "?") "css-retry=" attempt)))
            promise (js/Promise.
                      (fn [resolve reject]
                        (r/preinit href #js {:as "style" :precedence "modules" :fetchPriority "low"})
                        (if-let [element (link href)]
                          (let [*timeout (atom nil)]
                            (letfn [(finish! [error]
                                      (js/clearTimeout @*timeout)
                                      (.removeEventListener element "load" loaded!)
                                      (.removeEventListener element "error" failed!)
                                      (if error (reject error)
                                          (do (swap! *ready conj path) (resolve nil))))
                                    (loaded! [] (finish! nil))
                                    (failed! [] (finish! (js/Error. (str "Stylesheet could not load: " path))))]
                              (.addEventListener element "load" loaded!)
                              (.addEventListener element "error" failed!)
                              (reset! *timeout (js/setTimeout failed! 15000))
                              (when (.-sheet element) (loaded!))))
                          (reject (js/Error. "React did not register the stylesheet")))))
            retryable (.catch promise (fn [error]
                                        (swap! *requests dissoc path)
                                        (swap! *attempts update path (fnil inc 0))
                                        (throw error)))]
        (swap! *requests assoc path retryable)
        retryable)))

(defn acquire! [module]
  (if context/*server?*
    (js/Promise.resolve nil)
    (let [load! #(js/Promise.all (into-array (map acquire-path! (required-paths module))))]
      (if (and (= :deferred (get-in catalog/modules [module :ssr-styles])) (not (ready? module)))
        (js/Promise.
          (fn [resolve reject]
            ;; This sheet may itself block a mounted boundary's readiness.
            ;; Wait for hydration/paint, not for that same boundary to finish.
            (page/after-page! #(-> (load!) (.then resolve) (.catch reject)) {:layout? false})))
        (load!)))))
