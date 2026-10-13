(ns tolgraven.supabase.state
  "Connection identity shared with the lightweight managed-source adapter."
  (:require [tolgraven.react :as r]))

(defonce *client (r/atom nil))
(defonce *session (r/atom nil))
