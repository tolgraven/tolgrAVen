(ns tolgraven.dev.stack
  "Map current same-origin Shadow frames using ClojureScript's source-map decoder."
  (:require [clojure.string :as string]
            [cljs.source-map :as source-map]
            [tolgraven.react :as rf]
            [reagent.core :as r]
            [ajax.core :as ajax]
            [tolgraven.component.data :as data]
            [tolgraven.dev.source-links :as links]))

(defn frame [text]
  (when-let [[_ prefix file line column] (re-matches #"\s*(.*?)\(?((?:https?://|/)[^\s()]+):(\d+):(\d+)\)?\s*" text)]
    {:text text :function (string/trim prefix) :file file
     :line (js/parseInt line 10) :column (js/parseInt column 10)}))
(defn map-url [file origin]
  (try
    (let [url (js/URL. file origin)]
      (when (and (= origin (.-origin url))
                 (re-matches #"/js/compiled/out/cljs-runtime/[\w.$-]+\.js" (.-pathname url)))
        (str (.-pathname url) ".map")))
    (catch :default _ nil)))
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
(rf/reg-sub-raw :dev-stack/map
  (fn [_ [_ url]] (rf/make-reaction #(:value (data/snapshot (map-resource url))))))
(rf/reg-sub :dev-stack/decoded
  (fn [[_ url] _] (rf/subscribe [:dev-stack/map url]))
  (fn [payload _]
    (when payload
      (try (source-map/decode (clj->js payload))
           (catch :default _ nil)))))
(defn resources [stack origin]
  (into [links/catalog-resource]
    (for [url (take 20 (distinct (keep #(some-> (frame %) :file (map-url origin)) (string/split-lines stack))))]
      (map-resource url))))

(r/defc <frame> [catalog text]
  (let [parsed (frame text)
        url (when parsed (map-url (:file parsed) (.-origin js/location)))
        decoded (when url @(rf/subscribe [:dev-stack/decoded url]))
        position (when (and parsed decoded) (original-position decoded parsed))
        file (links/resolve-file catalog (:file position))]
    [:li (if file
           [:a {:href (links/source-url file (:line position))}
            (:function parsed) " · " file ":" (:line position) ":" (:column position)]
           [:code text])]))

(data/register-source! :dev-source-map
  {:load! (fn [{:keys [url]}]
            ;; A missing map is a normal unresolved frame, never a second error
            ;; boundary failure. Optional IO belongs to this managed adapter.
            (js/Promise.
              (fn [resolve _]
                (ajax/GET url {:timeout 5000
                               :response-format (ajax/json-response-format {:keywords? true})
                               :handler resolve :error-handler (fn [_] (resolve nil))}))))})

(r/defc <stack> [stack]
  (let [catalog (:value @(rf/subscribe [:component-data/installed links/catalog-path]))]
    (rf/use-effect
      #(rf/dispatch [:component-data/load (resources stack (.-origin js/location))]) [stack])
    [:ol.dev-stack
     (for [[index text] (map-indexed vector (string/split-lines stack))]
       ^{:key index} [<frame> catalog text])]))
