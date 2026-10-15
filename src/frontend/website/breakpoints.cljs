(ns website.breakpoints
  "Shared breaking-point subscriptions; boot owns the listener and dimension events."
  (:require [breaking-point.core :as bp]
            [website.page-layout :as layout]
            [website.react :as r]))

(def thresholds [:mobile 560 :tablet 992 :small-monitor 1200 :large-monitor])
(bp/register-subs thresholds)

(r/reg-sub :layout/responsive
  :<- [:breaking-point.core/screen]
  (fn [screen [_ values fallback]]
    (when screen (layout/responsive-value values (name screen) fallback))))
