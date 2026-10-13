(ns tolgraven.react.targets
  "Thin Shadow target adapter: server rendering and diagnostics stay out of the
   ordinary release browser graph. JVM source tooling sees inert declarations."
  (:require
    #?@(:dev [[reagent.dom.server] [re-frame.tooling]]
        :ssr [[reagent.dom.server]]
        :return-worker [[reagent.dom.server]]
        :default [])))

(def render-to-string
  #?(:dev reagent.dom.server/render-to-string
     :ssr reagent.dom.server/render-to-string
     :return-worker reagent.dom.server/render-to-string
     :default nil))
(def render-to-static-markup
  #?(:dev reagent.dom.server/render-to-static-markup
     :ssr reagent.dom.server/render-to-static-markup
     :return-worker reagent.dom.server/render-to-static-markup
     :default nil))

(def kind->id->handler #?(:dev re-frame.tooling/kind->id->handler :default nil))
(def live-query-vs #?(:dev re-frame.tooling/live-query-vs :default nil))
(def query->reaction #?(:dev re-frame.tooling/query->reaction :default nil))
(def query-v-for-reaction #?(:dev re-frame.tooling/query-v-for-reaction :default nil))
(def register-epoch-cb #?(:dev re-frame.tooling/register-epoch-cb :default nil))
(def register-trace-cb #?(:dev re-frame.tooling/register-trace-cb :default nil))
(def remove-epoch-cb #?(:dev re-frame.tooling/remove-epoch-cb :default nil))
(def remove-trace-cb #?(:dev re-frame.tooling/remove-trace-cb :default nil))
