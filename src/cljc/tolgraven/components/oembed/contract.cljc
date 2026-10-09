(ns tolgraven.components.oembed.contract
  #?(:clj (:import [java.net URI])))

(defn player-url
  "Only recognized HTTPS player endpoints may retain their provider origin.
   Arbitrary HTML/URLs must use the opaque-origin sandbox."
  [source]
  (when (string? source)
    (try
      (let [url #?(:clj (.normalize (URI. source)) :cljs (js/URL. source))
            scheme #?(:clj (.getScheme url) :cljs (.-protocol url))
            host #?(:clj (.getHost url) :cljs (.-hostname url))
            path #?(:clj (.getRawPath url) :cljs (.-pathname url))
            port #?(:clj (.getPort url) :cljs (.-port url))
            credentials? #?(:clj (some? (.getRawUserInfo url))
                            :cljs (or (seq (.-username url)) (seq (.-password url))))]
        (when (and (= #?(:clj "https" :cljs "https:") scheme)
                   (not credentials?)
                   (#?(:clj #{-1 443} :cljs #{"" "443"}) port)
                   (case host
                     "w.soundcloud.com" (= "/player/" path)
                     ("www.youtube.com" "www.youtube-nocookie.com")
                     (boolean (re-matches #"/embed/[A-Za-z0-9_-]{11}" path))
                     "player.vimeo.com" (boolean (re-matches #"/video/[0-9]+" path))
                     false))
          #?(:clj (.toASCIIString url) :cljs (.-href url))))
      (catch #?(:clj Exception :cljs :default) _ nil))))

(def result
  [:map
   [:html :string]
   [:title :string]
   [:height [:and number? [:> 0]]]
   [:player-src {:optional true} [:and :string [:fn player-url]]]])
