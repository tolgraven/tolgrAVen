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

(rf/reg-sub :nil (fn [_ _]))
