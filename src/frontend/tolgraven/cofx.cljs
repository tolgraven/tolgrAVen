(ns tolgraven.cofx
  (:require
    [reagent.core :as r]
    [tolgraven.react :as rf]
    [tolgraven.util :as util]))


(rf/reg-cofx :now         #(assoc % :now (js/Date.now)))
;; Legacy timer coeffect is also epoch milliseconds; its only consumers subtract.
(rf/reg-cofx :now-ct      #(assoc % :now-ct (js/Date.now)))

(rf/reg-cofx :user/gen-color
             #(assoc % :bg-color (util/css-str "hsla"
                                               (str (rand 360) "deg")
                                               (+ 0.1 (rand 0.2))
                                               (+ 0.2 (rand 0.1))
                                               0.5)))

(rf/reg-cofx :css-var
 (fn [cofx [css-var-k force-fetch?]]
   (let [value (if-let [cached-var (get-in (:db cofx) [:state :css-var css-var-k])]
                 (if-not force-fetch?
                   cached-var
                   (util/<-css-var (name css-var-k)))
                 (util/<-css-var (name css-var-k)))]
     (-> cofx
         (assoc-in [:css-var css-var-k] value)
         (assoc-in [:db :state :css-var css-var-k] value)))))

(defonce id-counters (atom {})) ;js has its own id gen thing so use that maybe. but no sequential then?
; silly using defonce and atom instead of (or rather parallel with) db right?
; already pulling an ugly one here messing with db so if doing that why not just bypass atom
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
 
(rf/reg-cofx :scroll-position
 (fn [cofx] ; 
   (assoc cofx :scroll-position (.-scrollY js/window)))) ;seems harmless enough. then persist.
   ; let [;el (or el (js/document.querySelector "main"))
   ;       ; prob want to support per-element scroll restoration later...
   ;       ; that'd be a whole other thing to support in generic navigate fn tho
   ;       ]

(rf/reg-cofx :css-vars
  (fn [cofx names]
    (assoc cofx :css-vars (into {} (map (fn [name] [name (util/<-css-var name)])) names))))
