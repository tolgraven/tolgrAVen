(ns tolgraven.subs
  (:require [re-frame.core :as rf]
            [tolgraven.db :as db]
            [clojure.edn :as edn]
            [cljs-time.core :as ct]
            ))
; [tolglow.util :as util :refer [css-str css-arg cmod make-key cs <sub]]

; (rf/reg-sub :<-- ;should this be discontinued? or only used transiently like migrate everything away once got a comp working?
(rf/reg-sub :get ;should this be discontinued? or only used transiently like migrate everything away once got a comp working?
 (fn [db [_ & path]]
  (get-in db (if (seqable? path) path [path])))) ;either way prob skip the destructuring and shit, runs too often...

(rf/reg-sub :nil (fn [_ _])) ; hah why ; from :text-color using it: "eh, worth? assuming this is a wrong-sub with no db input and we do have a lot of subs for this so"

(rf/reg-sub :content
 (fn [db [_ field]]
   ; (get-in db [:content (when field field)]))) ; cant work right?
   ; (get-in db [:content (if field [:content field])])
   (cond-> (get db :content)
     field field)))
