(ns tolgraven.supabase.state
  "Connection identity shared with the lightweight managed-source adapter."
  (:require [reagent.ratom :as ratom]))

(defonce *client (ratom/atom nil))
(defonce *session (ratom/atom nil))
