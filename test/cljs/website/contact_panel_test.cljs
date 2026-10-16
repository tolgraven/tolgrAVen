(ns website.contact-panel-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.events]
            [website.subs]
            [website.component.restore :as restore]
            [website.component.motion :as motion]
            [website.modules.contact.events]
            [website.modules.contact.views :as contact]
            [website.components.ui.inputs :as inputs]
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

(deftest untouched-and-cleared-text-fields-stay-empty-and-emit-strings
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))
                *changes (atom [])]
            (try
              (r/dispatch-sync [:form-field [:contact] {}])
              (await! (support/render! root
                        [inputs/<input-text>
                         :path [:form-field [:contact :name]]
                         :placeholder "Optional name"
                         :on-change (fn [& values] (swap! *changes conj (vec values)))]))
              (let [input (.querySelector element "input")]
                (is (= "" (.-value input)))
                (.focus input)
                (.blur input)
                (await! (support/settle!))
                (is (= [["" :blur]] @*changes)
                    "An untouched optional field must not emit nil on blur")
                (r/dispatch-sync [:form-field [:contact :name] "Ada"])
                (await! (support/wait-for! #(= "Ada" (.-value input))))
                (r/dispatch-sync [:form-field [:contact] {}])
                (await! (support/wait-for! #(= "" (.-value input))))
                (is (identical? input (.querySelector element "input"))
                    "Clearing external state retains the original controlled input")
                (.focus input)
                (.blur input)
                (await! (support/settle!))
                (is (= [["" :blur] ["" :blur]] @*changes)))
              (finally (close!)))))
        (.catch #(is false (str %)))
        (.finally done))))

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

(defn- focus-form [launcher?]
  [:div
   [:div
    (when launcher?
      [:button {:id "contact-focus-launcher", :on-click #(r/dispatch [:contact/open])}
       "Open contact"])]
   [contact/<contact-form-host>]])

(deftest close-control-returns-focus-to-the-opener-before-hiding-the-panel
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))]
            (try
              (await! (support/render! root (focus-form true)))
              (let [opener (.querySelector element "#contact-focus-launcher")]
                (.focus opener)
                (.click opener)
                (let [panel (await! (support/wait-for! #(.querySelector element ".contact-form-popup")))
                      close-button (.querySelector panel "button[aria-label=Close]")]
                  (await! (support/wait-for! #(.contains (.-classList panel) "appeared")))
                  (.focus close-button)
                  (.click close-button)
                  (is (identical? opener (.-activeElement js/document))
                      "The event moves focus before React renders the exit attributes")
                  (await! (support/wait-for! #(.contains (.-classList panel) "exiting")))
                  (is (not (.contains panel (.-activeElement js/document))))
                  (await! (support/wait-for! #(nil? (.querySelector element ".contact-form-popup"))))
                  (is (identical? opener (.-activeElement js/document)))))
              (finally (close!)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest closing-after-the-opener-disappears-releases-focused-descendants
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))]
            (try
              (await! (support/render! root (focus-form true)))
              (let [opener (.querySelector element "#contact-focus-launcher")]
                (.focus opener)
                (.click opener)
                (let [panel (await! (support/wait-for! #(.querySelector element ".contact-form-popup")))
                      close-button (.querySelector panel "button[aria-label=Close]")]
                  (await! (support/wait-for! #(.contains (.-classList panel) "appeared")))
                  (await! (support/render! root (focus-form false)))
                  (is (false? (.-isConnected opener)))
                  (.focus close-button)
                  (.click close-button)
                  (is (not (.contains panel (.-activeElement js/document))))
                  (await! (support/wait-for! #(nil? (.querySelector element ".contact-form-popup"))))))
              (finally (close!)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest explicit-contact-open-enters-after-a-restored-page
  (async done
    (-> (go-promise
          (doseq [context [{:back? true} {:hydrate? true}]]
            (let [{:keys [root element close!]} (await! (fixture!))
                  *initial (atom nil)
                  observer (js/MutationObserver.
                             (fn [_ _]
                               (when-let [panel (.querySelector element ".contact-form-popup")]
                                 (when-not @*initial
                                   (reset! *initial {:appeared? (.contains (.-classList panel) "appeared")})))))]
              (try
                ;; Hydration has ended, but a restored document retains its Back
                ;; policy for existing page content. Opening this form is new intent.
                (restore/begin! context)
                (restore/hydrated!)
                (await! (support/render! root [contact/<contact-form-host>]))
                (.observe observer element #js {:subtree true, :childList true})
                (r/dispatch-sync [:contact/open])
                (let [panel (await! (support/wait-for! #(.querySelector element ".contact-form-popup")))]
                  (when-not (motion/reduced-motion?)
                    (is (false? (:appeared? @*initial))
                        (str "A new form starts in its entrance state after " context)))
                  (await! (support/wait-for! #(.contains (.-classList panel) "appeared")))
                  (when-not (motion/reduced-motion?)
                    (is (pos? (.-length (.getAnimations panel))) "The native entrance actually runs"))
                  (r/dispatch-sync [:contact/close])
                  (await! (support/wait-for! #(.contains (.-classList panel) "exiting")))
                  (await! (support/wait-for! #(nil? (.querySelector element ".contact-form-popup")))))
                (finally (.disconnect observer) (close!))))))
        (.catch #(is false (str %)))
        (.finally done))))

(defn ^:export show-panel! []
  (restore/begin! {:back? true})
  (restore/hydrated!)
  (r/dispatch-sync [:contact/close])
  (-> (support/create-root! (.getElementById js/document "contact-demo"))
      (.then #(support/render! %
                [:<>
                 [:main.contact-motion-demo
                  [:h1 "Contact entrance"]
                  [:p "Open the form after a restored-page return."]
                  [:button {:on-click (fn [_] (r/dispatch [:contact/open]))} "Open contact"]]
                 [contact/<contact-form-host>]]))))
