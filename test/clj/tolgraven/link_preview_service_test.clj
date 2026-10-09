(ns tolgraven.link-preview-service-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-http.client :as http]
            [malli.core :as m]
            [tolgraven.modules.link-preview.article :as contract]
            [tolgraven.modules.link-preview.service :as service])
  (:import [java.net InetAddress ProxySelector]
           [java.io ByteArrayInputStream]))

(defn document [html & [headers]]
  {:url "https://example.com/posts/one"
   :headers (or headers {})
   :bytes (.getBytes html "UTF-8")})

(deftest extracts-readable-content-without-boilerplate-or-html
  (with-redefs [service/public-addresses! (fn [_] [])]
    (let [result (service/extract
                  (document (str "<title>Title</title><meta property='og:image' content='/hero.webp'>"
                                 "<meta name='description' content='Summary'><nav>Navigation</nav>"
                                 "<article><h1>Heading</h1><p>Readable <em>text</em></p>"
                                 "<blockquote><p>Quote once</p></blockquote>"
                                 "<pre><code>let x = 1;\n&lt;script&gt;</code></pre>"
                                 "<ul><li>First item</li></ul><script>alert(1)</script></article>"
                                 "<footer>Footer</footer>")
                            {"x-frame-options" "SAMEORIGIN"}))]
      (is (m/validate contract/result result))
      (is (= "Title" (:title result)))
      (is (= "Summary" (:description result)))
      (is (= "https://example.com/hero.webp" (:image result)))
      (is (= ["Heading" "Readable text" "Quote once" "let x = 1;\n<script>" "First item"]
             (mapv :text (:blocks result))))
      (is (= ["heading" "paragraph" "quote" "code" "item"] (mapv :kind (:blocks result))))
      (is (= "same-origin" (:frame-policy result))))))

(deftest extraction-is-bounded-and-recovers-malformed-documents
  (let [result (service/extract (document (str "<main><h2>Broken<p>Still readable"
                                              (apply str (repeat 80 (str "<p>" (apply str (repeat 300 "a"))))))))]
    (is (m/validate contract/result result))
    (is (<= (count (:blocks result)) 32))
    (is (<= (reduce + (map (comp count :text) (:blocks result))) service/max-text))
    (is (:truncated? result)))
  (is (= [] (:blocks (service/extract (document "<body><nav>Only navigation</nav>"))))))

(deftest detects-restrictive-frame-policies
  (doseq [[headers expected]
          [[{} "none"]
           [{"x-frame-options" "DENY"} "blocked"]
           [{"x-frame-options" "sameorigin"} "same-origin"]
           [{"content-security-policy" "default-src 'self'; frame-ancestors 'none'"} "blocked"]
           [{"content-security-policy" "frame-ancestors 'self'"} "same-origin"]
           [{"content-security-policy" "frame-ancestors *"} "none"]
           [{"content-security-policy" "frame-ancestors https://other.example"} "blocked"]
           [{"x-frame-options" "SAMEORIGIN" "content-security-policy" "frame-ancestors *"} "same-origin"]]]
    (is (= expected (service/frame-policy headers)))))

(deftest public-targets-only
  (doseq [address ["127.0.0.1" "0.0.0.0" "10.1.2.3" "100.64.0.1" "169.254.169.254"
                   "172.16.0.1" "192.168.0.1" "192.0.0.1" "198.18.0.1"
                   "198.51.100.1" "203.0.113.1" "224.0.0.1" "::1" "fc00::1"
                   "fe80::1" "2001:db8::1" "2002:7f00:1::1" "::ffff:127.0.0.1"]]
    (is (false? (service/public-address? (InetAddress/getByName address))) address))
  (doseq [address ["1.1.1.1" "8.8.8.8" "2606:4700:4700::1111"]]
    (is (service/public-address? (InetAddress/getByName address))))
  (doseq [url ["file:///etc/passwd" "http://user:pass@example.com" "http://example.com:4010/" "//example.com/"]]
    (is (thrown? Exception (service/checked-uri url))))
  (is (= 400 (:status (service/response! "file:///etc/passwd")))))

(deftest transport-disables-implicit-redirects-and-bounds-body
  (let [calls (atom [])]
    (with-redefs [http/get (fn [url options]
                            (swap! calls conj [url options])
                            {:status 302
                             :headers {"location" "http://127.0.0.1:4010/"}
                             :body (ByteArrayInputStream. (byte-array 0))})]
      (is (thrown? Exception (service/fetch-html! "https://example.com")))
      (is (= 1 (count @calls)))
      (let [options (second (first @calls))]
        (is (false? (:follow-redirects options)))
        (is (= 1 (count (:http-builder-fns options))))
        (is (= :ignore-cookies (:cookie-policy options)))))
    (with-redefs [http/get (fn [_ _]
                            {:status 200
                             :headers {"content-type" "text/html"}
                             :body (ByteArrayInputStream. (byte-array (inc service/max-bytes)))})]
      (is (thrown? Exception (service/fetch-html! "https://example.com"))))))

(deftest identical-preview-requests-share-acquisition
  (let [calls (atom 0)
        started (promise)
        release (promise)
        url "https://example.com/shared-preview-test"]
    (with-redefs [service/fetch-html! (fn [_]
                                      (swap! calls inc)
                                      (deliver started true)
                                      @release
                                      (document "<article><p>Shared result</p></article>"))]
      (let [a (future (service/preview! url))]
        @started
        (let [b (future (service/preview! url))]
          (deliver release true)
          (is (= @a @b))
          (is (= 1 @calls))
          (is (= @a (service/preview! url))))))))

(deftest connection-uses-the-checked-dns-resolver
  (let [hosts (atom [])]
    (with-redefs [service/public-addresses!
                  (fn [host]
                    (swap! hosts conj host)
                    (throw (ex-info "Non-public address" {})))]
      (is (thrown? Exception (service/fetch-html! "https://example.com/")))
      (is (= ["example.com"] @hosts)))))

(deftest public-fetch-does-not-use-system-proxy-routing
  (let [original (ProxySelector/getDefault)
        consulted (atom false)]
    (try
      (ProxySelector/setDefault
       (proxy [ProxySelector] []
         (select [_] (reset! consulted true) (throw (ex-info "Proxy consulted" {})))
         (connectFailed [_ _ _])))
      (with-redefs [service/public-addresses! (fn [_] (throw (ex-info "DNS blocked" {})))]
        (is (thrown? Exception (service/fetch-html! "https://example.com/")))
        (is (false? @consulted)))
      (finally (ProxySelector/setDefault original)))))

(deftest reports-clipped-single-blocks-as-truncated
  (let [result (service/extract (document (str "<article><p>" (apply str (repeat 5000 "x")) "</p></article>")))]
    (is (= 3000 (count (get-in result [:blocks 0 :text]))))
    (is (:truncated? result))
    (is (m/validate contract/result result))))

(deftest deeply-nested-fallback-content-is-scored-with-bounded-work
  (let [work (future
               (service/extract
                 (document (str (apply str (repeat 12000 "<div>"))
                                "<p>Deep readable content</p>"
                                (apply str (repeat 12000 "</div>"))))))]
    (try
      (let [result (deref work 5000 ::timeout)]
        (is (not= ::timeout result))
        (when (map? result)
          (is (= "Deep readable content" (get-in result [:blocks 0 :text])))))
      (finally (future-cancel work)))))

(deftest evicted-failed-preview-does-not-recreate-a-poisoned-cache-entry
  (let [cache @#'service/*cache
        url "https://example.com/eviction-regression"
        calls (atom 0)]
    (swap! cache dissoc url)
    (try
      (with-redefs [service/fetch-html! (fn [_]
                                        (if (= 1 (swap! calls inc))
                                          (do (swap! cache dissoc url)
                                              (throw (Exception. "Evicted request failed")))
                                          (document "<main><p>Recovered content</p></main>")))]
        (is (= "unavailable" (:status (service/preview! url))))
        (is (not (contains? @cache url)))
        (is (= "ready" (:status (service/preview! url))))
        (is (= 2 @calls)))
      (finally (swap! cache dissoc url)))))
