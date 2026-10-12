(ns tolgraven.app
  (:require [tolgraven.core :as core]
            [shadow.loader :as shadow-loader]))

;;ignore println statements in prod
(set! *print-fn* (fn [& _]))

(defn ^:export init! []
  ;; A return-render worker reuses these same immutable browser chunks, without
  ;; starting a site lifecycle or network bindings in its isolated runtime.
  (when (exists? js/window) (core/init!)))

(defn ^:export worker-import! [id url]
  (shadow-loader/set_load_start id)
  (js/importScripts url)
  (shadow-loader/set_loaded id))

