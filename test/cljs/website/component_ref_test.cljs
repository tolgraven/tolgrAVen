(ns website.component-ref-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.component]
            [website.macros :refer-macros [defc]]
            [website.test-support :as support]))

(defc <ref-root> {:features [:props [:appear "opacity"]]} [spec]
  [:div {:ref (:body-ref spec)} "Ref owner"])

(deftest forwarded-cleanup-and-legacy-refs-release-on-replacement-and-unmount
  (async done
    (let [*events (atom [])
          *element (atom nil)
          legacy (fn [element] (reset! *element element) nil)
          callback (fn [element]
                     (swap! *events conj [:attach element])
                     #(swap! *events conj [:release element]))]
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root
                          [<ref-root> {:body-ref legacy, :props {:ref callback}}]))
                (let [native (.-firstElementChild element)]
                  (is (identical? native @*element))
                  (is (= [[:attach native]] @*events))
                  (await! (support/render! root [<ref-root> {:body-ref legacy}]))
                  (is (identical? native (.-firstElementChild element)))
                  (is (identical? native @*element))
                  (is (= [[:attach native] [:release native]] @*events)
                      "React cleanup runs instead of calling the callback with nil"))
                (finally (support/unmount! root)))
              (is (nil? @*element))
              (is (= 2 (count @*events)))))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest each-native-attachment-returns-its-own-cleanup
  (let [*events (atom [])
        reference (fn [element]
                    (swap! *events conj [:attach element])
                    #(swap! *events conj [:release element]))
        callback (r/compose-refs reference reference nil)
        first-element (js-obj)
        second-element (js-obj)
        release-first! (callback first-element)
        release-second! (callback second-element)]
    (is (= [[:attach first-element] [:attach second-element]] @*events)
        "Distinct React attachments do not release each other")
    (release-first!)
    (is (= [:release first-element] (last @*events)))
    (release-second!)
    (is (= [:release second-element] (last @*events)))
    (is (nil? (callback nil)))))

(deftest plain-ref-callbacks-use-the-normal-node-and-nil-contract
  (let [*element (atom nil)
        reference #js {:current nil}
        callback (r/compose-refs #(reset! *element %) reference)
        element (js-obj)]
    (is (nil? (callback element)) "Plain callbacks do not opt into React 19 cleanup")
    (is (identical? element @*element))
    (is (identical? element (.-current reference)))
    (callback nil)
    (is (nil? @*element))
    (is (nil? (.-current reference)))))
