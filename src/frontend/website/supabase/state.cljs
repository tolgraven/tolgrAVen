(ns website.supabase.state
  "Connection identity shared with the lightweight managed-source adapter."
  (:require [website.react :as r]))

(defonce *client (r/atom nil))
(defonce *session (r/atom nil))
