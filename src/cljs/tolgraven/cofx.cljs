(ns tolgraven.cofx
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [tolgraven.util :as util]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))


(rf/reg-cofx :now         #(assoc % :now (ctc/to-long (ct/now))))

(rf/reg-cofx :user/gen-color
             #(assoc % :bg-color (util/css-str "hsla"
                                               (rand 360)
                                               (+ 0.1 (rand 0.2))
                                               (+ 0.2 (rand 0.1))
                                               0.5)))

(defonce id-counters (atom {})) ;js has its own id gen thing so use that maybe. but no sequential then?
(rf/reg-cofx :gen-id
 (fn [cofx [k & [parent-id]]] ;however would manage to pass hahah
   (let [k (or k :id)]
     (-> cofx
         (assoc :id {:id (swap! id-counters update k (fnil inc -1)) ;here rather, uh sub parent by id, check index, inc
                     :parent-id parent-id
                     :uuid (random-uuid)})
         (assoc-in [:db :state :id-counters] @id-counters))))) ;seems harmless enough. then persist.

(rf/reg-fx :id-counters/set!
 (fn [state]
   (reset! id-counters state)))

; use re-frame-utils ::inject to inject subs!
; caveat also needs deref in a view
