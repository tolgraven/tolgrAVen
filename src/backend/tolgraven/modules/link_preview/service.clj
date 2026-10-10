(ns tolgraven.modules.link-preview.service
  "Bounded public HTML acquisition and readable extraction. No browser parser."
  (:require [tolgraven.cache.inflight :as inflight]
            [clj-http.client :as http]
            [clj-http.conn-mgr :as connections]
            [clojure.string :as string])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.net InetAddress URI]
           [java.util ArrayDeque]
           [java.util.concurrent Semaphore]
           [org.apache.http.conn DnsResolver]
           [org.apache.http.impl.conn DefaultRoutePlanner DefaultSchemePortResolver]
           [org.apache.http.impl.client HttpClientBuilder]
           [org.jsoup Jsoup]
           [org.jsoup.nodes Element TextNode]
           [org.jsoup.select NodeTraversor NodeVisitor]))

(def max-bytes (* 1024 1024))
(def max-text 10000)
(def cache-ttl-ms 3600000)
(defonce ^:private *cache (atom {}))
(defonce ^:private slots (Semaphore. 4))

(defn public-address? [^InetAddress address]
  (let [bytes (mapv #(bit-and 255 %) (.getAddress address))
        [a b c] bytes]
    (and (not (or (.isAnyLocalAddress address)
                  (.isLoopbackAddress address)
                  (.isLinkLocalAddress address)
                  (.isSiteLocalAddress address)
                  (.isMulticastAddress address)))
         (if (= 4 (count bytes))
           (not (or (= a 0) (= a 10) (= a 127) (>= a 224)
                    (and (= a 100) (<= 64 b 127))
                    (and (= a 169) (= b 254))
                    (and (= a 172) (<= 16 b 31))
                    (and (= a 192) (or (= b 168) (= b 0)
                                      (and (= b 88) (= c 99))))
                    (and (= a 198) (or (#{18 19} b)
                                      (and (= b 51) (= c 100))))
                    (and (= a 203) (= b 0) (= c 113))))
           ;; Only globally routable IPv6; excludes ULA, mapped/translation and
           ;; link-local ranges. Documentation/Teredo/6to4 are not fetch targets.
           (and (= 0x20 (bit-and a 0xe0))
                (not (and (= a 0x20) (= b 0x01)
                          (or (= c 0) (and (= c 0x0d) (= 0xb8 (nth bytes 3))))))
                (not (and (= a 0x20) (= b 0x02))))))))

(defn checked-uri [url]
  (let [uri (URI. url)
        scheme (some-> (.getScheme uri) string/lower-case)]
    (when-not (and (#{"http" "https"} scheme)
                   (.getHost uri)
                   (nil? (.getUserInfo uri))
                   (or (= -1 (.getPort uri))
                       (= (.getPort uri) (if (= "https" scheme) 443 80))))
      (throw (ex-info "Invalid public preview URL" {})))
    uri))

(defn public-addresses! [host]
  (let [addresses (InetAddress/getAllByName host)]
    (when-not (and (seq addresses) (every? public-address? addresses))
      (throw (ex-info "Preview target is not public" {})))
    addresses))

(defn- read-bounded! [^InputStream input deadline]
  (with-open [input input
              output (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop [size 0]
        (when (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Preview deadline exceeded" {})))
        (let [n (.read input buffer)]
          (if (neg? n)
            (.toByteArray output)
            (let [size (+ size n)]
              (when (> size max-bytes)
                (throw (ex-info "Preview document too large" {})))
              (.write output buffer 0 n)
              (recur size))))))))

(defn fetch-html!
  "Validate every redirect and the actual connection's DNS resolution. No proxy,
   cookies, credentials or implicit redirects; limit decompressed bytes and time."
  [url]
  (let [deadline (+ (System/currentTimeMillis) 8000)
        resolver (reify DnsResolver
                   (resolve [_ host] (public-addresses! host)))]
    (with-open [manager (connections/make-regular-conn-manager
                        {:dns-resolver resolver :socket-timeout 3000})]
      (loop [uri (checked-uri url) redirects 0]
        (when (or (> redirects 3) (> (System/currentTimeMillis) deadline))
          (throw (ex-info "Preview redirect/deadline limit exceeded" {})))
        (let [{:keys [status headers body]}
              (http/get (str uri)
                        {:connection-manager manager
                         :as :stream
                         :throw-exceptions false
                         :follow-redirects false
                         :max-redirects 0
                         :http-builder-fns [(fn [^HttpClientBuilder builder _]
                                              (.setRoutePlanner builder
                                                (DefaultRoutePlanner. DefaultSchemePortResolver/INSTANCE)))]
                         :retry-handler (fn [_ _ _] false)
                         :cookie-policy :ignore-cookies
                         :conn-timeout 2000
                         :socket-timeout 3000
                         :headers {"Accept" "text/html,application/xhtml+xml"
                                   "User-Agent" "tolgraven-link-preview/1.0"}})]
          (cond
            (#{301 302 303 307 308} status)
            (do (when body (.close ^InputStream body))
                (recur (checked-uri (str (.resolve uri (get headers "location" ""))))
                       (inc redirects)))
            (and (= 200 status)
                 (re-find #"(?i)^(text/html|application/xhtml\+xml)(;|$)"
                          (get headers "content-type" "")))
            {:url (str uri)
             :headers headers
             :bytes (read-bounded! body deadline)}
            :else
            (do (when body (.close ^InputStream body))
                (throw (ex-info "Readable preview unavailable" {})))))))))

(defn- clip [s limit]
  (subs (or s "") 0 (min limit (count (or s "")))))

(defn frame-policy [headers]
  (let [xfo (string/upper-case (get headers "x-frame-options" ""))
        csp (get headers "content-security-policy" "")
        ancestors (re-seq #"(?i)(?:^|[;,])\s*frame-ancestors\s+([^;,]+)" csp)]
    (cond
      (or (string/includes? xfo "DENY")
          (some #(not (#{"*" "'self'"} (string/trim (second %)))) ancestors)) "blocked"
      (or (string/includes? xfo "SAMEORIGIN")
          (some #(= "'self'" (string/trim (second %))) ancestors)) "same-origin"
      ;; Unknown XFO directives are treated conservatively in optional miniatures.
      (not (string/blank? xfo)) "blocked"
      :else "none")))

(defn unavailable [url]
  {:status "unavailable"
   :url url
   :title ""
   :description "This page does not provide a readable preview. Follow the link to view it."
   :blocks []
   :truncated? false
   :frame-policy "blocked"})

(defn- metadata [document selector]
  (some-> (.selectFirst document selector) (.attr "content") string/trim not-empty))

(defn- content-root [document]
  ;; Score every subtree once, in postorder. Repeated .text/.select calls for
  ;; nested candidates can otherwise traverse the same 1 MiB body quadratically.
  (let [stack (ArrayDeque.)
        *semantic (volatile! nil)
        *fallback (volatile! nil)
        choose! (fn [best element score]
                  (when (or (nil? @best) (> score (:score @best)))
                    (vreset! best {:element element :score score})))]
    (NodeTraversor/traverse
      (reify NodeVisitor
        (head [_ node _]
          (cond
            (instance? Element node) (.push stack (long-array 2))
            (instance? TextNode node)
            (when-let [^longs stats (.peek stack)]
              (aset-long stats 0 (+ (aget stats 0)
                                   (count (string/trim (.getWholeText ^TextNode node))))))))
        (tail [_ node _]
          (when (instance? Element node)
            (let [^Element element node
                  ^longs stats (.pop stack)
                  text (aget stats 0)
                  links (if (= "a" (.tagName element)) text (aget stats 1))
                  score (- text (* 2 links))
                  tag (.tagName element)]
              (when (or (#{"article" "main"} tag) (= "main" (.attr element "role")))
                (choose! *semantic element score))
              (when (#{"section" "div"} tag)
                (choose! *fallback element score))
              (when-let [^longs parent (.peek stack)]
                (aset-long parent 0 (+ (aget parent 0) text))
                (aset-long parent 1 (+ (aget parent 1) links)))))))
      document)
    (or (:element @*semantic) (:element @*fallback) (.body document))))

(defn extract
  "Use semantic article/main content where available, otherwise the densest body
   region. Emit only bounded text blocks and a public image URL, never HTML."
  [{:keys [url headers bytes]}]
  (let [document (Jsoup/parse (java.io.ByteArrayInputStream. bytes) nil url)
        title (clip (or (metadata document "meta[property=og:title]")
                        (.title document)) 300)
        description (clip (or (metadata document "meta[property=og:description]")
                              (metadata document "meta[name=description]")) 600)
        _ (.remove (.select document "script,style,noscript,nav,footer,aside,form,[hidden],[aria-hidden=true],.cookie-banner,.advertisement"))
        root (content-root document)
        elements (.select ^Element root "h1,h2,h3,h4,p,blockquote,pre,li")
        blocks (->> elements
                    ;; Outer quote/code/list items own nested paragraphs once.
                    (remove #(some (fn [^Element ancestor]
                                     (and (not= root ancestor)
                                          (#{"blockquote" "pre" "li"} (.tagName ancestor))))
                                   (.parents ^Element %)))
                    (keep (fn [^Element element]
                            (let [tag (.tagName element)
                                  text (if (= tag "pre") (.wholeText element) (.text element))
                                  text (string/trim text)]
                              (when (seq text)
                                (cond-> {:kind (cond
                                                 (re-matches #"h[1-4]" tag) "heading"
                                                 (= tag "blockquote") "quote"
                                                 (= tag "pre") "code"
                                                 (= tag "li") "item"
                                                 :else "paragraph")
                                         :text text}
                                  (re-matches #"h[1-4]" tag)
                                  (assoc :level (Integer/parseInt (subs tag 1))))))))
                    vec)
        blocks (if (seq blocks) blocks
                   (let [text (string/trim (.text ^Element root))]
                     (if (seq text) [{:kind "paragraph" :text text}] [])))
        bounded (loop [remaining blocks selected [] size 0]
                  (if-let [block (first remaining)]
                    (if (or (= 32 (count selected)) (>= size max-text)) selected
                        (let [block (update block :text clip (min 3000 (- max-text size)))]
                          (recur (next remaining) (conj selected block)
                                 (+ size (count (:text block)))))) selected))
        raw-image (or (metadata document "meta[property=og:image]")
                      (metadata document "meta[name=twitter:image]")
                      (some-> (.selectFirst ^Element root "img[src]") (.absUrl "src") not-empty))
        image (when raw-image
                (try
                  (let [uri (checked-uri (str (.resolve (URI. url) raw-image)))]
                    (public-addresses! (.getHost uri))
                    (str uri))
                  (catch Exception _ nil)))]
    (cond-> {:status "ready"
             :url url
             :title title
             :description description
             :blocks bounded
             :truncated? (not= bounded blocks)
             :frame-policy (frame-policy headers)}
      image (assoc :image image))))

(defn preview!
  "Share in-flight reads and bound acquisition/cache memory. Failed upstream pages
   are ordinary preview fallbacks, cached briefly rather than repeated on hover."
  [url]
  (checked-uri url)
  (let [now (System/currentTimeMillis)
        [owner? entry]
        (inflight/acquire! *cache url now cache-ttl-ms 128)]
    (when owner?
      (let [acquired? (.tryAcquire slots)
            result (if acquired?
                     (try (extract (fetch-html! url))
                          (catch Exception _ (unavailable url))
                          (finally (.release slots)))
                     (unavailable url))]
        (when (= "unavailable" (:status result))
          (inflight/shorten! *cache url entry (+ now 60000)))
        (deliver (:value entry) result)))
    (if entry (deref (:value entry) 12000 (unavailable url)) (unavailable url))))

(defn response! [url]
  (try
    {:status 200
     :headers {"Cache-Control" "private, max-age=60"}
     :body (preview! url)}
    (catch Exception _ {:status 400 :body {:error "Invalid preview URL"}})))
