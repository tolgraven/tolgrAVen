(ns website.footer-accessibility-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [website.react :as r]
            [website.components.shell :as shell]
            [website.events]
            [website.subs]
            [website.test-support :as support]))

(deftest only-the-active-footer-is-accessible
  (async done
    (let [restore! (r/make-restore-fn)
          element (.createElement js/document "div")
          *root (atom nil)
          content [{:id :contact, :email "footer@example.test"}]
          inactive? (fn [selector]
                      (let [footer (.querySelector element selector)]
                        (and (= "true" (.getAttribute footer "aria-hidden"))
                             (.hasAttribute footer "inert"))))]
      (.appendChild (.-body js/document) element)
      ;; Exercise the real native-root observer, including layout changes
      ;; without a scroll event. Test-only positioning keeps the footer distant.
      (set! (.. element -style -position) "absolute")
      (set! (.. element -style -top) "calc(100vh + 20rem)")
      (r/dispatch-sync [:state [:scroll :at-bottom] false])
      (r/dispatch-sync [:state [:hidden :footer] false])
      (-> (support/create-root! element)
          (.then (fn [root]
                   (reset! *root root)
                   (support/render! root [:<> [shell/<footer> content] [shell/<footer-full> content]])))
          (.then (fn [_]
                   (is (inactive? "#footer-end") "Distant full footer has no accessible or focusable duplicate")
                   (is (not (inactive? "#footer-sticky")))
                   (set! (.. element -style -top) "0rem")
                   (support/wait-for! #(not (inactive? "#footer-end")))))
          (.then (fn [_]
                   (is (not (inactive? "#footer-end")) "Full footer becomes accessible near the page end")
                   (is (inactive? "#footer-sticky") "The retired sticky copy is inert")
                   (set! (.. element -style -top) "calc(100vh + 20rem)")
                   (r/dispatch-sync [:state [:hidden :footer] true])
                   (support/wait-for! #(inactive? "#footer-end"))))
          (.then (fn [_]
                   (is (inactive? "#footer-end"))
                   (is (inactive? "#footer-sticky") "A visually hidden sticky footer cannot receive focus")))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (restore!)
                      (done)))))))
