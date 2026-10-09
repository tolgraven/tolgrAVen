(ns tolgraven.oembed-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [tolgraven.component.data :as data]
            [tolgraven.components.oembed :as oembed]
            [tolgraven.test-support :as support]))

(deftest provider-html-runs-without-access-to-the-application-origin
  (async done
    (let [source (get @data/*sources :url)
          *resolve (atom nil)
          *message (atom nil)
          url "https://soundcloud.com/example/sandbox-regression"
          element (.createElement js/document "div")
          listen! (fn [event]
                    (when (= "oembed-sandbox-regression" (.-type (.-data event)))
                      (reset! *message (js->clj (.-data event) :keywordize-keys true))))]
      (.appendChild (.-body js/document) element)
      (.addEventListener js/window "message" listen!)
      (data/register-source! :url
        {:load! (fn [{:keys [url]}]
                  (is (= "/api/oembed?url=https%3A%2F%2Fsoundcloud.com%2Fexample%2Fsandbox-regression" url))
                  (js/Promise. (fn [resolve _] (reset! *resolve resolve))))})
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (try
                (await! (support/render! root [oembed/<oembed-view> url [:p "Loading player"]]))
                (await! (support/wait-for! #(deref *resolve)))
                (is (.includes (.-textContent element) "Loading player"))
                (@*resolve {:height 166
                            :html (str "<p id='provider-content'>Provider</p><script>"
                                       "let blocked=false;try{parent.document.body.dataset.oembedEscaped='yes'}"
                                       "catch(e){blocked=true}parent.postMessage({type:'oembed-sandbox-regression',blocked},'*');"
                                       "</script>")})
                (await! (support/wait-for! #(deref *message)))
                (is (true? (:blocked @*message)))
                (is (nil? (.querySelector element "#provider-content")))
                (is (not (.hasAttribute (.-body js/document) "data-oembed-escaped")))
                (let [frame (.querySelector element "iframe.oembed-inner")]
                  (is (= "allow-scripts" (.getAttribute frame "sandbox")))
                  (is (= "no-referrer" (.getAttribute frame "referrerpolicy"))))
                (finally (support/unmount! root)))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (.removeEventListener js/window "message" listen!)
                      (.remove element)
                      (data/invalidate! #(= (:url %) (:url (oembed/dependency url))))
                      (data/register-source! :url source)
                      (done)))))))
