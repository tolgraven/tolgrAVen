(ns media.videos
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [media.images :as images]))

(def codecs
  {"vp9" ["-c:v" "libvpx-vp9" "-crf" "30" "-b:v" "0" "-cpu-used" "2" "-row-mt" "1"]
   "av1" ["-c:v" "libsvtav1" "-crf" "35" "-preset" "6" "-svtav1-params" "fast-decode=1:tune=0"]})

(defn -main [& args]
  (let [{:keys [force? files]} (images/arguments args)
        files (or (seq files) (fs/glob "resources/public" "**.{mp4,MP4}"))
        results
        (doall
          (for [file files, [codec options] codecs
                :let [target (fs/path (str (fs/strip-ext file) "-" codec ".webm"))]]
            (if (and (not force?) (fs/exists? target)
                     (not (pos? (compare (fs/last-modified-time file) (fs/last-modified-time target)))))
              :skipped
              (let [temporary (fs/create-temp-file {:dir (or (fs/parent target) ".")
                                                    :prefix "video-"
                                                    :suffix ".webm"})]
                (try
                  (apply process/shell (into ["ffmpeg" "-hide_banner" "-loglevel" "error" "-i" (str file)]
                                       (concat options ["-an" "-y" (str temporary)])))
                  (fs/set-posix-file-permissions temporary "rw-r--r--")
                  (fs/move temporary target {:replace-existing true :atomic-move true})
                  (println "Created:" (str target))
                  :converted
                  (finally (fs/delete-if-exists temporary)))))))]
    (println "Video conversion:" (frequencies results))))
