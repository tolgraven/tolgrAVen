(ns tolgraven.render-context
  "Request context shared by ordinary views during SSR and initial hydration."
  (:require [tolgraven.react :as r]))

(def ^:dynamic *server?* false)
(def ^:dynamic *modules* nil)
;; A synchronous server render owns this optional export inventory; it is never UI state.
(def ^:dynamic *rendered-views* nil)
(def ^:dynamic *href* nil)
(defonce *snapshot (r/atom nil))
(defonce *interactive? (r/atom true))
(defn href [default route params query]
  ((or *href* default) route params query))
