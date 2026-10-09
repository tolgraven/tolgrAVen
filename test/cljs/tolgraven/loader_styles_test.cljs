(ns tolgraven.loader-styles-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [shadow.lazy :as lazy]
            [tolgraven.loader :as loader]
            [tolgraven.loader.style-catalog :as catalog]
            [tolgraven.loader.styles :as styles]))

(deftest shared-font-stylesheet-has-one-bundle-across-consumers
  (let [font "/css/tolgraven/modules/monospace.min.css"
        bundle (catalog/stylesheet-bundle font)]
    (is (= [font] (get catalog/stylesheet-bundles bundle)))
    (is (some #{bundle} (catalog/bundle-names :markdown)))
    (is (some #{bundle} (catalog/bundle-names :styled-input)))
    (is (= 1 (count (filter #{font}
                           (catalog/paths (into {} (map (fn [[id spec]] [id (:paths spec)])) catalog/modules)
                                          :search)))))))

(deftest css-starts-before-shadow-and-both-must-settle-before-install
  (async done
    (let [id (keyword (str (random-uuid)))
          original-modules loader/modules
          original-css styles/acquire!
          original-load lazy/load
          *started (atom [])
          *code (atom nil)
          *css (atom nil)
          *installed (atom false)
          spec {:id id :install #(reset! *installed true)}
          css (js/Promise. (fn [resolve _] (reset! *css resolve)))
          code (js/Promise. (fn [resolve _] (reset! *code resolve)))
          loadable (reify lazy/ILoadable
                     (ready? [_] false)
                     IDeref (-deref [_] spec))]
      (set! loader/modules {id loadable})
      (set! lazy/load (fn ([_] (swap! *started conj :js) code)
                         ([_ _] code)))
      (set! styles/acquire! (fn [_] (swap! *started conj :css) css))
      (let [first-load (loader/acquire-code! id)]
        (is (= [:css :js] @*started) "Styles start synchronously, before Shadow code acquisition")
        (is (identical? first-load (loader/acquire-code! id)) "Concurrent callers share both acquisitions")
        (@*code spec)
        (-> (js/Promise.resolve nil)
            (.then (fn [_]
                     (is (false? @*installed) "Fast JavaScript cannot expose an unstyled module")
                     (@*css nil)
                     first-load))
            (.then (fn [loaded] (is (= spec loaded)) (is (true? @*installed))))
            (.catch (fn [error] (is false (str error))))
            (.finally (fn []
                        (set! loader/modules original-modules)
                        (set! styles/acquire! original-css)
                        (set! lazy/load original-load)
                        (swap! loader/*code-loads dissoc id)
                        (swap! loader/*installed disj id)
                        (done))))))))

(deftest stylesheet-adapter-deduplicates-real-loads-and-retries-failed-resources
  (async done
    (let [url (str "/css/tolgraven/modules/cv.min.css?test=" (random-uuid))
          missing (str "/css/missing-" (random-uuid) ".css")
          first-load (styles/acquire-path! url)]
      (is (identical? first-load (styles/acquire-path! url)))
      (-> first-load
          (.then (fn [_]
                   (is (contains? @styles/*ready url))
                   (is (some #(and (.includes (.-href %) url) (.-sheet %))
                             (array-seq (.querySelectorAll js/document "link[rel=stylesheet]"))))
                   (-> (styles/acquire-path! missing)
                       (.then (fn [_] (is false "Missing CSS must reject readiness")))
                       (.catch (fn [_]
                                 (is (nil? (get @styles/*requests missing)))
                                 (is (= 1 (get @styles/*attempts missing)))
                                 (let [retry (styles/acquire-path! missing)]
                                   (is (some #(.includes (.-href %) (str missing "?css-retry=1"))
                                             (array-seq (.querySelectorAll js/document "link[rel=stylesheet]"))))
                                   (.catch retry (fn [_] (is (= 2 (get @styles/*attempts missing)))))))))))
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))

(deftest initial-inline-css-satisfies-acquisition-without-a-duplicate-request
  (async done
    (let [url (str "/bundles/inline-" (random-uuid) "/module.css")
          sheet (.createElement js/document "style")
          probe (.createElement js/document "div")]
      (.setAttribute sheet "data-module-style" url)
      (set! (.-textContent sheet) ".inline-module-fixture {font-weight:700}")
      (set! (.-className probe) "inline-module-fixture")
      (.appendChild (.-head js/document) sheet)
      (.appendChild (.-body js/document) probe)
      (let [request (styles/acquire-path! url)]
        (is (identical? request (styles/acquire-path! url)))
        (-> request
            (.then (fn [_]
                     (is (contains? @styles/*ready url))
                     (is (= "700" (.-fontWeight (js/getComputedStyle probe))))
                     (is (not-any? #(= (.-pathname (js/URL. (.-href %))) url)
                                   (array-seq (.querySelectorAll js/document "link[rel=stylesheet]")))
                         "Ready inline CSS does not call React preinit again")))
            (.catch (fn [error] (is false (str error))))
            (.finally (fn []
                        (.remove sheet)
                        (.remove probe)
                        (swap! styles/*ready disj url)
                        (swap! styles/*requests dissoc url)
                        (done))))))))
