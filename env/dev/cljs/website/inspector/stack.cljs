(ns website.inspector.stack
  "Map current same-origin Shadow frames using ClojureScript's source-map decoder."
  (:require [cljs.source-map :as source-map]
            [clojure.string :as string]
            [website.browser.location :as location]
            [website.component.data :as data]
            [website.http :as http]
            [website.inspector.source-links :as links]
            [website.react :as r]))

(defn frame [text]
  (when-let [[_ prefix file line column] (re-matches #"\s*(.*?)\(?((?:https?://|/)[^\s()]+):(\d+):(\d+)\)?\s*" text)]
    {:text text :function (string/trim prefix) :file file
     :line (parse-long line) :column (parse-long column)}))

(defn map-url [file origin]
  (when-let [path (location/local-path file origin)]
    (when (re-matches #"/js/compiled/out/cljs-runtime/[\w.$-]+\.js" path)
      (str path ".map"))))

(defn original-position [decoded {:keys [line column]}]
  ;; Both dimensions in v3 maps are zero based. Never pretend a generated JS
  ;; line is an original line when no mapping exists at/before this column.
  (when (and (pos? line) (pos? column))
    (let [columns (get decoded (dec line))
          key (last (sort (filter #(<= % (dec column)) (keys columns))))
          segment (last (get columns key))]
      (when (:source segment)
        {:file (:source segment) :line (inc (:line segment))
         :column (inc (:col segment))}))))

(defn map-resource [url] {:source :dev-source-map :url url :ttl-ms 1})

(r/reg-sub-raw :dev-stack/map
  (fn [_ [_ url]] (r/make-reaction #(:value (data/snapshot (map-resource url))))))

(r/reg-sub :dev-stack/decoded
  (fn [[_ url] _] (r/subscribe [:dev-stack/map url]))
  (fn [payload _]
    (when payload
      (try (source-map/decode (clj->js payload))
           (catch :default _ nil)))))

(defn resources [stack origin]
  (let [source-map-url #(some-> (frame %) :file (map-url origin))]
    (->> (string/split-lines stack)
         (keep source-map-url)
         distinct
         (take 20)
         (into [links/catalog-resource] (map map-resource)))))

(r/defc <frame> [catalog text]
  (let [parsed (frame text)
        url (when parsed (map-url (:file parsed) (location/origin)))
        decoded (when url @(r/subscribe [:dev-stack/decoded url]))
        position (when (and parsed decoded) (original-position decoded parsed))
        file (links/resolve-file catalog (:file position))]
    [:li (if file
           [:a {:href (links/source-url file (:line position))}
            (:function parsed) " · " file ":" (:line position) ":" (:column position)]
           [:code text])]))

(defn ^:async load-source-map! [{:keys [url]}]
  (try
    (await (http/request! {:uri url :timeout 5000 :response-format :json}))
    ;; Missing maps leave the original JavaScript frame visible.
    (catch :default _ nil)))

(data/register-source! :dev-source-map {:load! load-source-map!})

(r/defc <stack> [stack]
  (let [catalog (:value @(r/subscribe [:component-data/installed links/catalog-path]))]
    (r/use-effect
      #(r/dispatch [:component-data/load (resources stack (location/origin))]) [stack])
    [:ol.dev-stack
     (for [[index text] (map-indexed vector (string/split-lines stack))]
       ^{:key index} [<frame> catalog text])]))
