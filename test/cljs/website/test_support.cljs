(ns website.test-support
  "Reagent fixtures for isolated component tests. Initial mounting is asynchronous;
   updates use a stable reactive form, never raw React root renders or flushSync."
  (:require [cljs.core.async :as a]
            [website.async :as p]
            [website.clock :as clock]
            [website.react :as r]))

(defn result-channel [pending]
  (let [channel (a/promise-chan)]
    (-> (p/resolved pending)
        (p/then #(a/put! channel {:value %}))
        (p/catch-error #(a/put! channel {:error %})))
    channel))

(defn wait-for!
  ([predicate] (wait-for! predicate "Mounted view did not settle"))
  ([predicate label]
   (let [deadline (+ (clock/epoch-ms) 3000)]
     (p/create
       (fn [resolve reject]
         (letfn [(check! []
                   (try
                     (if-let [value (predicate)]
                       (resolve value)
                       (if (> (clock/epoch-ms) deadline)
                         (reject (js/Error. label))
                         (r/after-render check!)))
                     (catch :default error (reject error))))]
           (r/after-render check!)))))))

(defn ^:async create-root! [element]
  (let [root (r/create-root element)
        *form (r/atom nil)
        *mounted? (atom false)
        fixture (r/create-class {:component-did-mount #(reset! *mounted? true)
                                 :reagent-render (fn [] @*form)})]
    (try
      (r/render root [fixture])
      (await (wait-for! #(deref *mounted?)))
      {:root root :form *form}
      (catch :default error
        (r/unmount root)
        (throw error)))))

(defn settle! []
  (p/create (fn [resolve _] (r/after-render #(r/after-render resolve)))))

(defn render! [{:keys [form]} value]
  (reset! form value)
  (settle!))

(defn unmount! [root]
  (r/unmount (if (map? root) (:root root) root)))

(r/defc <subscription-consumer> [queries *values]
  (let [values (into {} (map (fn [[key query]] [key @(r/subscribe query)])) queries)]
    ;; Retain the actual router/controller values for the calling test.
    (r/use-layout-effect (fn [] (reset! *values values) nil) [values *values])
    [:pre (pr-str values)]))

(defn ^:async mount-subscriptions! [queries]
  (let [element (.createElement js/document "div")
        *values (atom {})]
    (.appendChild (.-body js/document) element)
    (let [root (try (await (create-root! element))
                    (catch :default error
                      (.remove element)
                      (throw error)))
          dispose! (delay (try (unmount! root) (finally (.remove element))))]
      (try
        (await (render! root [<subscription-consumer> queries *values]))
        {:values #(deref *values) :unmount! #(force dispose!)}
        (catch :default error
          (force dispose!)
          (throw error))))))

(defn ^:async subscription-value! [query]
  (let [{:keys [values unmount!]} (await (mount-subscriptions! {:value query}))]
    (try (:value (values))
         (finally (unmount!)))))

(defn state-at! [path]
  (subscription-value! (into [:get] path)))
