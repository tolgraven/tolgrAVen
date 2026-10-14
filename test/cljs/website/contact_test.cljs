(ns website.contact-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.components.contact :as contact]
            [website.events]
            [website.subs]
            [website.test-support :as support]))

(deftest server-email-text-does-not-expose-a-rewritable-mailto-anchor
  (let [html (r/render-to-string [contact/<contact-ways> "hello@example.com"])]
    (is (.includes html "hello@example.com"))
    (is (not (.includes html "mailto:")))
    (is (not (.includes html "href=")))
    (is (.includes html "Contact us by form"))))

(deftest hydration-keeps-the-email-node-and-enables-its-link-after-commit
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                form [contact/<contact-ways> "hello@example.com"]
                *errors (atom [])
                console-error (.-error js/console)]
            ;; Test fixture DOM is outside production React ownership.
            (set! (.-innerHTML element) (r/render-to-string form))
            (.appendChild (.-body js/document) element)
            (let [anchor (.querySelector element "a")
                  text (.-firstChild anchor)]
              (is (nil? (.getAttribute anchor "href")))
              (set! (.-error js/console) (fn [& args] (swap! *errors conj args)))
              (let [root (r/hydrate-root element form)]
                (try
                  (await! (support/wait-for! #(= "mailto:hello@example.com"
                                                 (.getAttribute anchor "href"))))
                  (is (identical? anchor (.querySelector element "a")))
                  (is (identical? text (.-firstChild anchor)))
                  (is (= "hello@example.com" (.-textContent anchor)))
                  (is (empty? @*errors) "Initial hydration has no attribute mismatch or recovery")
                  (finally
                    (support/unmount! root)
                    (set! (.-error js/console) console-error)
                    (.remove element)))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest committed-email-links-follow-prop-updates-and-clean-up-their-frame
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                *cancelled (atom [])
                cancel-frame js/cancelAnimationFrame]
            (.appendChild (.-body js/document) element)
            (set! js/cancelAnimationFrame
                  (fn [frame] (swap! *cancelled conj frame) (cancel-frame frame)))
            (try
              (await! (support/render! root [contact/<contact-ways> "first@example.com"]))
              (let [anchor (.querySelector element "a")]
                (await! (support/wait-for! #(= "mailto:first@example.com"
                                               (.getAttribute anchor "href"))))
                ;; Model a mail-handler extension rewriting an already committed link.
                (.setAttribute anchor "href" "https://mail.example.com/compose?to=first@example.com")
                (.setAttribute anchor "target" "_blank")
                (await! (support/render! root [contact/<contact-ways> "second@example.com"]))
                (is (identical? anchor (.querySelector element "a")))
                (is (= "mailto:second@example.com" (.getAttribute anchor "href")))
                (is (= "second@example.com" (.-textContent anchor)))
                (await! (support/render! root [:p "Unmounted"]))
                (is (seq @*cancelled) "Unmount releases the component-owned animation frame"))
              (finally
                (support/unmount! root)
                (set! js/cancelAnimationFrame cancel-frame)
                (.remove element)))))
        (.catch #(is false (str %)))
        (.finally done))))
