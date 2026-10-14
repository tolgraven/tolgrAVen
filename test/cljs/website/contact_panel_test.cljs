(ns website.contact-panel-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.events]
            [website.subs]
            [website.component.restore :as restore]
            [website.modules.contact.events]
            [website.modules.contact.views :as contact]
            [website.test-support :as support]))

(defn- fixture! []
  (go-promise
    (let [before @r/*app-db
          context @restore/*context
          element (.createElement js/document "div")
          style (.createElement js/document "style")
          root (await! (support/create-root! element))]
      (set! (.-textContent style)
            ".contact-form-popup {opacity:0;transition:opacity .3s linear} .contact-form-popup.appeared {opacity:1} .contact-form-popup.exiting {opacity:0}")
      (.appendChild (.-head js/document) style)
      (.appendChild (.-body js/document) element)
      (restore/begin! {})
      (r/dispatch-sync [:contact/close])
      {:root root
       :element element
       :close! #(do (support/unmount! root)
                    (.remove element)
                    (.remove style)
                    (reset! restore/*context context)
                    (reset! r/*app-db before))})))

(deftest contact-panel-mounts-on-intent-and-unmounts-after-native-exit
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))]
            (try
              (await! (support/render! root [contact/<contact-form-host>]))
              (is (nil? (.querySelector element ".contact-form-popup")))
              (r/dispatch-sync [:contact/open])
              (let [panel (await! (support/wait-for! #(.querySelector element ".contact-form-popup")))]
                (await! (support/wait-for! #(.contains (.-classList panel) "appeared")))
                (r/dispatch-sync [:contact/close])
                (await! (support/wait-for! #(.contains (.-classList panel) "exiting")))
                (is (identical? panel (.querySelector element ".contact-form-popup")))
                (is (= "true" (.getAttribute panel "aria-hidden")))
                (is (.hasAttribute panel "inert"))
                (await! (support/wait-for! #(nil? (.querySelector element ".contact-form-popup"))))
                (is (nil? (.querySelector element "input")) "Closed forms retain no inputs or hidden panel"))
              (finally (close!)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest reopening-during-exit-retains-the-panel-and-its-draft
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))]
            (try
              (await! (support/render! root [contact/<contact-form-host>]))
              (r/dispatch-sync [:form-field [:contact :email] "draft@example.com"])
              (r/dispatch-sync [:contact/open])
              (let [panel (await! (support/wait-for! #(.querySelector element ".contact-form-popup")))]
                (await! (support/wait-for! #(.contains (.-classList panel) "appeared")))
                (r/dispatch-sync [:contact/close])
                (await! (support/wait-for! #(.contains (.-classList panel) "exiting")))
                (r/dispatch-sync [:contact/open])
                (await! (support/wait-for! #(not (.contains (.-classList panel) "exiting"))))
                (is (identical? panel (.querySelector element ".contact-form-popup")))
                (is (not (.hasAttribute panel "inert")))
                (is (= "draft@example.com" (.-value (.querySelector panel "input[type=email]"))))
                (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 400))))
                (is (identical? panel (.querySelector element ".contact-form-popup"))
                    "Cancelled exit cannot remove the reopened form"))
              (finally (close!)))))
        (.catch #(is false (str %)))
        (.finally done))))
