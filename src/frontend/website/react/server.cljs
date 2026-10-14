(ns website.react.server
  "Explicit server-rendering facade for the lazy page-render module.
   Ordinary browser components must not require this adapter."
  (:require [reagent.dom.server :as server]))

(def render-to-string server/render-to-string)
(def render-to-static-markup server/render-to-static-markup)
