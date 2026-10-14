(ns website.modules.github.diff
  "Pure GitHub revision/path planning and bounded text decoding."
  (:require [clojure.string :as string]
            [website.modules.github.detail-schema :as detail-schema]))

(def revision-schema [:tuple :string :string [:re #"^[0-9a-fA-F]{40}$"]])

(def max-bytes (* 1024 1024))
(defn valid-revision? [[user repo sha :as revision]]
  (and (= 3 (count revision)) (string? user) (re-matches #"[A-Za-z0-9_-]+" user)
       (string? repo) (re-matches #"[A-Za-z0-9_.-]+" repo) (not (#{"." ".."} repo))
       (string? sha) (re-matches #"[0-9a-fA-F]{40}" sha)))
(defn key-for [revision] (string/join "/" revision))
(defn page-url [revision] (when (valid-revision? revision) (str "/neovim/github/" (key-for revision))))
(defn- query-target [[user repo]] (str "user=" (js/encodeURIComponent user) "&repo=" (js/encodeURIComponent repo)))
(defn commit-url [[_ _ sha :as revision]] (str "/api/github/commits/" sha "?" (query-target revision)))
(defn metadata-url [revision page] (str (commit-url revision) "&page=" page))
(defn patch-url [[_ _ sha :as revision]] (str "/api/github/commits/" sha "/patch?" (query-target revision)))
(defn safe-path? [path]
  (detail-schema/safe-path? path))
(defn sides [commit file]
  (let [parent (get-in commit [:parents 0 :sha])
        removed? (= "removed" (:status file))
        added? (or (= "added" (:status file)) (nil? parent))]
    {:before (when-not added? {:sha parent, :path (or (:previous_filename file) (:filename file))})
     :after (when-not removed? {:sha (:sha commit), :path (:filename file)})}))
(defn contents-url [revision {:keys [sha path]}]
  (str "/api/github/contents?" (query-target revision)
       "&ref=" sha "&path=" (js/encodeURIComponent path)))
(defn decode-text [{:keys [type encoding content size]}]
  (when-not (and (= "file" type) (= "base64" encoding) (string? content)
                 (number? size) (<= 0 size max-bytes))
    (throw (js/Error. "Only UTF-8 text files up to 1 MiB can be compared here.")))
  (when (> (count content) (+ (* 2 max-bytes) 4096))
    (throw (js/Error. "The encoded source file is too large.")))
  (let [raw (js/atob (string/replace content #"\s" ""))
        bytes (js/Uint8Array.from raw (fn [char] (.charCodeAt char 0)))
        text (try (.decode (js/TextDecoder. "utf-8" #js {:fatal true}) bytes)
                  (catch :default _
                    (throw (js/Error. "Binary or non-UTF-8 files cannot be compared as text."))))]
    (when (or (> (.-length bytes) max-bytes) (.includes text "\u0000"))
      (throw (js/Error. "Binary or oversized files cannot be compared as text.")))
    text))
(defn document [mode file content]
  (case mode
    :patch {:mode :patch, :files [{:path "commit.patch", :text (:patch content)}]}
    :diff {:mode :diff
           :files [{:path (str "before/" (or (:previous_filename file) (:filename file)))
                    :text (get-in content [:files (:filename file) :before])}
                   {:path (str "after/" (:filename file))
                    :text (get-in content [:files (:filename file) :after])}]}))
