(ns tolgraven.component-ref-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [tolgraven.component.ref :as refs]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.test-support :as support]))

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

(deftest ref-cleanup-is-idempotent-and-cannot-release-a-later-attachment
  (let [reference #js {:current nil}
        *events (atom [])
        legacy #(swap! *events conj %)
        callback (refs/compose reference legacy legacy nil)
        first-element (js-obj)
        second-element (js-obj)
        release-first! (callback first-element)
        release-second! (callback second-element)]
    (is (= [first-element nil second-element] @*events) "Duplicate references attach once")
    (release-first!)
    (is (identical? second-element (.-current reference)))
    (release-second!)
    (release-second!)
    (callback nil)
    (is (nil? (.-current reference)))
    (is (= [first-element nil second-element nil] @*events))))

(deftest failed-ref-attachment-and-cleanup-release-the-other-owners
  (let [reference #js {:current nil}
        attach-error (js/Error. "Attach failed")
        cleanup-error (js/Error. "Cleanup failed")
        failed-attach (refs/compose reference (fn [_] (throw attach-error)))
        failed-cleanup (refs/compose reference (fn [_] #(throw cleanup-error)))]
    (is (identical? attach-error (try (failed-attach (js-obj)) (catch :default error error))))
    (is (nil? (.-current reference)))
    (let [release! (failed-cleanup (js-obj))]
      (is (identical? cleanup-error (try (release!) (catch :default error error))))
      (is (nil? (.-current reference)))
      (is (nil? (release!)) "Failed cleanup still releases exactly once"))))
