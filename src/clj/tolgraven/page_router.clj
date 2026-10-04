(ns tolgraven.page-router
  "Server router composed from the same local specs as the browser router."
  (:require [reitit.core :as reitit]
            [tolgraven.main.pages :as main]
            [tolgraven.blog.pages :as blog]
            [tolgraven.cv.pages :as cv]
            [tolgraven.docs.pages :as docs]))

(def router
  (reitit/router
   (into [""] (concat main/spec blog/spec cv/spec docs/spec))))

(defn match [path] (reitit/match-by-path router path))
