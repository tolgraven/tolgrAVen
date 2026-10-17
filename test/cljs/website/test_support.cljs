(ns website.test-support
  "Reagent fixtures for isolated component tests. Initial mounting is asynchronous;
   updates use a stable reactive form, never raw React root renders or flushSync."
  (:require [cljs.core.async :as a]
            [website.react :as r]

            ))
(defn result-channel
  [promise]
  (let [channel (a/promise-chan)]
    (-> (js/Promise.resolve promise)
        (.then #(a/put! channel {:value %}))
        (.catch #(a/put! channel {:error %})))
    channel))
(defn wait-for!
  ([predicate] (wait-for! predicate "Mounted view did not settle"))
  ([predicate label]
  (js/Promise. (fn [resolve reject]
                 (let [deadline (+ (.now js/Date) 3000)]
                   (letfn [(check! []
                             (try (if-let [value (predicate)]
                                    (resolve value)
                                    (if (> (.now js/Date) deadline)
                                      (reject (js/Error. label))
                                      (r/after-render check!)))
                                  (catch :default error (reject error))))]
                     (r/after-render check!)))))))
(defn create-root!
  [element]
  (let [root (r/create-root element)
        *form (r/atom nil)
        *mounted? (atom false)
        <fixture> (r/create-class {:component-did-mount (fn [_] (reset! *mounted? true)),
                                   :reagent-render (fn [] @*form)})]
    (r/render root [<fixture>])
    (-> (wait-for! #(deref *mounted?))
        (.then (fn [_] {:root root, :form *form}))
        (.catch (fn [error] (r/unmount root) (throw error))))))
(defn settle! [] (js/Promise. (fn [resolve _] (r/after-render #(r/after-render resolve)))))
(defn render! [{:keys [form]} value] (reset! form value) (settle!))
(defn unmount! [root] (r/unmount (if (map? root) (:root root) root)))
(r/defc <subscription-consumer> [queries *values]
  (let [values (into {} (map (fn [[key query]] [key @(r/subscribe query)])) queries)]
    ;; Keep opaque native route/controller values intact. The rendered text is
    ;; diagnostic output, not a serialization transport for subscription data.
    (r/use-layout-effect (fn [] (reset! *values values) nil) [values *values])
    [:pre (pr-str values)]))

(defn mount-subscriptions!
  [queries]
  (let [element (.createElement js/document "div")
        *mounted? (atom true)
        *values (atom {})]
    (.appendChild (.-body js/document) element)
    (-> (create-root! element)
        (.then (fn [root]
                 (-> (render! root [<subscription-consumer> queries *values])
                     (.then (fn [_]
                              {:values #(deref *values),
                               :unmount! (fn []
                                           (when (compare-and-set! *mounted? true false)
                                             (unmount! root)
                                             (.remove element)))})))))
        (.catch (fn [error] (.remove element) (throw error))))))
(defn subscription-value!
  [query]
  (-> (mount-subscriptions! {:value query})
      (.then (fn [{:keys [values unmount!]}] (try (:value (values)) (finally (unmount!)))))))
(defn state-at! [path] (subscription-value! (into [:get] path)))