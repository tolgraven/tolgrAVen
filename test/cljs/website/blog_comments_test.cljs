(ns website.blog-comments-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [reitit.frontend :as reitit]
            [reitit.frontend.easy :as rfe]
            [website.navigation.routes :as routes]
            [website.test-support :as support]
            [website.component.restore :as restore]
            [website.modules.blog.views :as blog]
            [website.modules.blog.comment-views :as comment-views]
            [website.modules.blog.comments :as comments]
            [website.modules.blog.events]
            [website.modules.blog.subs]
            [website.modules.user.module :as user-module]
            [website.supabase.scoped :as scoped]
            [website.loader :as loader]
            [shadow.lazy :as lazy]
            [website.events]
            [website.subs]))

(defn- tick! [ms] (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(deftest clipped-replies-enter-and-survive-repeated-fast-folds
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                before @r/*app-db
                context @restore/*context
                subscribe (r/subscribe-function)
                modules loader/modules
                author {:id "writer", :name "Writer"}
                *active-user (r/atom nil)
                comment (fn [id parent]
                          {:id id
                           :parent-post 42
                           :parent-comment parent
                           :user "writer"
                           :title id
                           :text "Reply body"
                           :ts 1})
                replies (comment "reply" "root")
                grandchild (assoc (comment "grandchild" "reply") :reply-count 0)]
            (.appendChild (.-body js/document) element)
            (try
              (restore/begin! {:hydrate? false, :back? false})
              (r/dispatch-sync [:set [:common/route] (reitit/match-by-path routes/router "/blog/post/42")])
              (await! (loader/load! {:module :markdown}))
              (doseq [[parent rows] [["root" [replies]] ["reply" [grandchild]] ["grandchild" []]]]
                (r/dispatch-sync [:store/scoped (scoped/query-key (comments/thread-query 42 parent))
                                  {:docs (mapv #(hash-map :id (:id %) :data %) rows)}]))
              (r/with-framework-bindings
                [loader/modules
                 (assoc modules :user (reify lazy/ILoadable
                          (ready? [_] true)
                          IDeref
                          (-deref [_] (dissoc user-module/spec :content :depends))))
                 rfe/href (fn [name params query]
                            (some-> (reitit/match-by-name routes/router name params)
                                    (reitit/match->path query)))
                 r/subscribe
                 (fn
                   ([q]
                    (case (first q)
                      :store/on-snapshot
                      (or (when (get-in @r/*app-db [:store :scoped (scoped/query-key (second q))])
                            (subscribe [:store/scoped (scoped/query-key (second q))]))
                          (r/atom {:docs []}))
                      :user/user (r/atom author)
                      :user/active-user *active-user
                      :comments/adding? (r/atom false)
                      (subscribe q)))
                   ([q opts] (subscribe q opts)))]
                (await! (support/render! root
                         [:section {:style {:position "fixed", :top "0", :width "50vw"}}
                          [comment-views/<comment-post> {:path [42 "root"]
                                                :focused-id "root"
                                                :comment (comment "root" nil)}]]))
                (await! (tick! 250))
                (is (= 3 (.-length (.querySelectorAll element ".blog-comment-around"))))
                (is (= 3 (count (re-seq #"Reply body" (.-textContent element))))
                    "The real Markdown bodies render, rather than dependency error fallbacks")
                (is (nil? (.querySelector element ".blog-comment-view-less-btn"))
                    "Focusing short text does not invent a collapse arrow")
                (let [actions (.querySelector element ".blog-comment-actions")
                      link (.querySelector actions ".blog-comment-permalink")]
                  (is (= 2 (.-length (.querySelectorAll actions ".blog-comment-action"))))
                  (is (= "/blog/post/42/comment/root" (.getAttribute link "href")))
                  (is (= "Permalink" (.getAttribute link "aria-label")))
                  (is (nil? (.getAttribute link "title")) "Labels belong to our UI")
                  (.focus link)
                  (await! (support/settle!))
                  (is (= "Permalink" (.-textContent (.querySelector actions "[role=tooltip]"))))
                  (.dispatchEvent link (js/KeyboardEvent. "keydown" #js {:key "Escape", :bubbles true}))
                  (await! (support/settle!))
                  (is (nil? (.querySelector actions "[role=tooltip]")))
                  (.blur link)
                  (.dispatchEvent link (js/PointerEvent. "pointerover" #js {:bubbles true}))
                  (await! (support/settle!))
                  (is (.querySelector actions "[role=tooltip]") "Hover opens the same custom label")
                  (.dispatchEvent link (js/PointerEvent. "pointerout" #js {:bubbles true}))
                  (reset! *active-user author)
                  (await! (support/settle!))
                  (is (= 4 (.-length (.querySelectorAll actions ".blog-comment-action")))
                      "Own comments retain both navigation links, Reply and Edit")
                  (reset! *active-user {:id "reader", :name "Reader"})
                  (await! (support/settle!))
                  (is (= 3 (.-length (.querySelectorAll actions ".blog-comment-action"))))
                  (is (nil? (.querySelector actions ".blog-comment-edit-btn")))
                  (reset! *active-user nil)
                  (await! (support/settle!)))
                (is (zero? (.-length (.querySelectorAll element ".appear-wrapper:not(.appeared)")))
                    "Initial child transforms cannot trap visible replies")
                ;; Each pair finishes open, including reversals before the
                ;; delayed child-unmount and before its entrance completes.
                (dotimes [_ 5]
                  (.click (.querySelector element ".blog-comment-border"))
                  (await! (tick! 40))
                  (.click (.querySelector element ".blog-comment-border"))
                  (await! (tick! 40)))
                (await! (tick! 350))
                (is (= 3 (.-length (.querySelectorAll element ".blog-comment-around"))))
                (is (= "zoom-out" (.. js/window (getComputedStyle (.querySelector element ".blog-comment-border")) -cursor)))
                (.click (.querySelector element ".blog-comment-border"))
                (await! (support/wait-for!
                          #(= 1 (.-length (.querySelectorAll element ".blog-comment-around")))
                          "Folded children did not unmount after delayed visibility"))
                (is (= 1 (.-length (.querySelectorAll element ".blog-comment-around"))))
                (is (re-find #"1\s+hidden reply" (.-textContent element))
                    "A legacy comment without reply-count retains its cached children/control")
                (.click (.querySelector element ".blog-comment-border"))
                (await! (tick! 300))
                (is (= 3 (.-length (.querySelectorAll element ".blog-comment-around"))))
                (is (= 3 (count (re-seq #"Reply body" (.-textContent element)))))
                (is (zero? (.-length (.querySelectorAll element ".appear-wrapper:not(.appeared)")))))
              (finally
                (support/unmount! root)
                (.remove element)
                (reset! r/*app-db before)
                (reset! restore/*context context)))))
        (.catch #(is false (str %)))
        (.finally done))))
