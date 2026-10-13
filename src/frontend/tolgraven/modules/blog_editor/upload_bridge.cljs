(ns tolgraven.modules.blog-editor.upload-bridge
  "SDK-required Promises observe the existing serialized event queue; no transport here."
  (:require [tolgraven.react :as r]))

(defonce ^:private *waiters (atom {}))

(r/reg-fx :blog-editor/settle-upload
  (fn [{:keys [id src error]}]
    (when-let [{:keys [resolve reject]} (get @*waiters id)]
      (swap! *waiters dissoc id)
      (if error (reject (js/Error. error)) (resolve src)))))

(defn upload! [owner file]
  (js/Promise.
    (fn [resolve reject]
      (let [id (str (random-uuid))]
        (swap! *waiters assoc id {:resolve resolve, :reject reject})
        (r/dispatch [:blog/image-upload-enqueue
                     [{:id id, :name (.-name file), :file file, :owner owner, :sdk? true}] 0 0])))))
