(ns tolgraven.link-preview.util
  (:require
    [clojure.string :as string]
    [goog.string :as gstring]
    [tolgraven.routes :as routes]))

(def external-http-url? routes/external-http-url?)
(def prefetch-delay-ms {:trusted 250, :user 1500})

(defn href-variants [url]
  [url (try
         (.-href (js/URL. (gstring/unescapeEntities url)))
         (catch :default _ url))])

(defn- trim-url-token [url]
  (loop [url (string/replace url #"[.,;:!?]+$" "")]
    (let [closer (last url)
          opener ({\) \( \] \[ \} \{} closer)]
      (if (and opener
               (> (count (filter #{closer} url))
                  (count (filter #{opener} url))))
        (recur (subs url 0 (dec (count url))))
        url))))

(defn external-urls
  "Extract distinct external HTTP(S) URLs from raw markdown, HTML, or text."
  [text base-url]
  (->> (re-seq #"(?:https?:)?//[^\s<>\"']+" (or text ""))
       (map trim-url-token)
       (keep #(try
                (.-href (js/URL. % base-url))
                (catch :default _ nil)))
       (filter #(external-http-url? % base-url))
       distinct
       vec))
