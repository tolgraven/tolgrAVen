(ns tolgraven.test-support
  "Reagent fixtures for isolated component tests. Initial mounting is asynchronous;
   updates use a stable reactive form, never raw React root renders or flushSync."
  (:require [cljs.reader :as reader]
            [cljs.core.async :as a]
            [tolgraven.react :as rf]
            [reagent.core :as r]
            [reagent.dom.client :as dom]))
(defn result-channel
  [promise]
  (let [channel (a/promise-chan)]
    (-> (js/Promise.resolve promise)
        (.then #(a/put! channel {:value %}))
        (.catch #(a/put! channel {:error %})))
    channel))
(defn wait-for!
  [predicate]
  (js/Promise. (fn [resolve reject]
                 (let [deadline (+ (.now js/Date) 3000)]
                   (letfn [(check! []
                             (try (if-let [value (predicate)]
                                    (resolve value)
                                    (if (> (.now js/Date) deadline)
                                      (reject (js/Error. "Mounted view did not settle"))
                                      (r/after-render check!)))
                                  (catch :default error (reject error))))]
                     (r/after-render check!))))))
(defn create-root!
  [element]
  (let [root (dom/create-root element)
        *form (r/atom nil)
        *mounted? (atom false)
        <fixture> (r/create-class {:component-did-mount (fn [_] (reset! *mounted? true)),
                                   :reagent-render (fn [] @*form)})]
    (dom/render root [<fixture>])
    (-> (wait-for! #(deref *mounted?))
        (.then (fn [_] {:root root, :form *form}))
        (.catch (fn [error] (dom/unmount root) (throw error))))))
(defn settle! [] (js/Promise. (fn [resolve _] (r/after-render #(r/after-render resolve)))))
(defn render! [{:keys [form]} value] (reset! form value) (settle!))
(defn unmount! [root] (dom/unmount (if (map? root) (:root root) root)))
(defn mount-subscriptions!
  [queries]
  (let [element (.createElement js/document "div")
        *mounted? (atom true)
        <consumer> (fn [] [:pre
                           (pr-str (into {} (map (fn [[k q]] [k @(rf/subscribe q)])) queries))])]
    (.appendChild (.-body js/document) element)
    (-> (create-root! element)
        (.then (fn [root]
                 (-> (render! root [<consumer>])
                     (.then (fn [_]
                              {:values (fn [] (reader/read-string (.-textContent element))),
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