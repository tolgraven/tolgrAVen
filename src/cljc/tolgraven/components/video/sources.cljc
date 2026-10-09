(ns tolgraven.components.video.sources
  (:require [clojure.string :as string]
            #?(:clj [tolgraven.build.videos :refer [responsive-videos]]))
  #?(:cljs (:require-macros [tolgraven.build.videos :refer [responsive-videos]])))

(def responsive (responsive-videos))

(defn variants [src]
  (when-let [{:keys [width media]} (get responsive (string/replace (or src "") #"^/" ""))]
    (let [base (string/replace src #"\.mp4$" (str "-" width "w"))]
      [{:src (str base "-av1.webm")
        :type "video/webm; codecs=av01.0.05M.08"
        :media media}
       {:src (str base "-vp9.webm")
        :type "video/webm; codecs=vp9"
        :media media}
       {:src (str base ".mp4")
        :type "video/mp4"
        :media media}])))
