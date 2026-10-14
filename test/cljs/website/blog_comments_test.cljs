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
            [website.modules.blog.presentation :as presentation]
            [website.modules.user.module :as user-module]
            [website.supabase.scoped :as scoped]
            [website.loader :as loader]
            [website.loader.styles :as styles]
            [shadow.lazy :as lazy]
            [website.events]
            [website.subs]))

(defn- tick! [ms] (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(deftest standalone-root-edit-renders-the-parent-path-editor
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                restore-db! (r/make-restore-fn)
                before-context @restore/*context
                subscribe (r/subscribe-function)
                author {:id "writer", :name "Writer"}
                row {:id "113"
                     :user "writer"
                     :parent-post 42
                     :parent-comment "hidden-parent"
                     :path [42 "hidden-parent"]
                     :reply-count 0
                     :title "Selected comment"
                     :text "Original standalone text"
                     :ts 1}
                status (r/atom {:ready? true
                                :comment-id "113"
                                :row row
                                :root row
                                :parent {:id 42, :title "Parent post"}})
                *active-user (r/atom author)
                modules loader/modules
                ready (fn [spec]
                        (reify lazy/ILoadable
                          (ready? [_] true)
                          IDeref
                          (-deref [_] (dissoc spec :content :depends))))]
            (try
              (.appendChild (.-body js/document) element)
              (r/dispatch-sync [:init/app-db])
              (restore/begin! {})
              (r/dispatch-sync [:set [:common/route]
                                (assoc (reitit/match-by-path routes/router "/blog/comment/113")
                                       :parameters {:path {:comment-id "113"}, :query {}})])
              (await! (loader/load! {:module :markdown}))
              (await! (loader/load! {:module :styled-input}))
              (await! (styles/acquire! :user))
              (r/with-framework-bindings
                [loader/modules (assoc modules :user (ready user-module/spec))
                 rfe/href (fn [name params query]
                            (some-> (reitit/match-by-name routes/router name params) (reitit/match->path query)))
                 r/subscribe
                 (fn
                   ([q]
                    (case (first q)
                      :comments/focus-ready? (r/atom true)
                      :comments/focus-state status
                      :user/user (r/atom author)
                      :user/active-user *active-user
                      (subscribe q)))
                   ([q opts] (subscribe q opts)))]
                (await! (support/render! root
                          [:<>
                           [:link {:rel "stylesheet" :href "/css/website/main.css"}]
                           [:link {:rel "stylesheet" :href "/css/website/modules/blog.css"}]
                           [comment-views/<standalone-comment> "113"]]))
                (await! (support/wait-for!
                          #(and (= "absolute" (.-position (js/getComputedStyle (.querySelector element ".blog-comment-actions"))))
                                (not= "" (.getPropertyValue (js/getComputedStyle (.-documentElement js/document)) "--space")))))
                (await! (.-ready (.-fonts js/document)))
                (await! (support/settle!))
                (let [comment (.querySelector element "section.blog-comment")
                      actions (.querySelector comment ".blog-comment-actions")
                      bounds (.getBoundingClientRect comment)
                      controls (.getBoundingClientRect actions)
                      height (.-height bounds)]
                  (is (= 4 (.-childElementCount actions)) "Links, Edit and Reply have one corner owner")
                  (is (every? #(.contains (.-classList %) "noborder") (array-seq (.-children actions))))
                  (is (< (.-top controls) (+ (.-top bounds) (/ height 2))) "The action group stays in the original top corner")
                  (reset! *active-user nil)
                  (await! (support/settle!))
                  (is (= 2 (.-childElementCount actions)))
                  (is (= "absolute" (.-position (js/getComputedStyle actions)))
                      "Authentication keeps the shared actions out of the comment's text flow")
                  (reset! *active-user author)
                  (await! (support/settle!)))
                (.click (.querySelector element ".blog-comment-edit-btn"))
                (await! (support/wait-for! #(.querySelector element ".blog-adding-comment .md-editor-content"))))
              (is (.querySelector element ".blog-adding-comment") "Edit renders a real comment editor")
              (is (= "Original standalone text" (.-textContent (.querySelector element ".md-editor-content"))))
              (is (= "113" (:id (await! (support/subscription-value! [:blog/state [:editing-comment [42]]])))))
              (is (= "Original standalone text" (:text (await! (support/subscription-value! [:form-field [:write-comment [42]]])))))
              (finally
                (support/unmount! root)
                (.remove element)
                (reset! restore/*context before-context)
                (restore-db!)))))
        (.catch #(is false (str %)))
        (.finally done))))

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
                  (is (= "Permalink" (.-textContent (.getElementById js/document (.getAttribute link "aria-describedby")))))
                  (.dispatchEvent link (js/KeyboardEvent. "keydown" #js {:key "Escape", :bubbles true}))
                  (await! (support/settle!))
                  (is (nil? (.querySelector js/document ".blog-comment-action-label")))
                  (.blur link)
                  (.dispatchEvent link (js/PointerEvent. "pointerover" #js {:bubbles true}))
                  (await! (support/settle!))
                  (is (.querySelector js/document ".blog-comment-action-label") "Hover opens the same custom label")
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

(deftest trusted-comment-markdown-never-renders-site-components-or-iframes
  (async done
    (let [element (.createElement js/document "div")
          restore-db! (r/make-restore-fn)
          root (atom nil)]
      (.appendChild (.-body js/document) element)
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            ;; Earlier fixtures restore app-db, including code readiness. Pair
            ;; this fresh state with fresh acquisition promises, while reusing
            ;; the actual loaded JavaScript and stylesheet resources.
            (swap! loader/*loads dissoc :markdown)
            (swap! loader/*code-loads dissoc :markdown)
            (await! (loader/load! {:module :markdown}))
            (let [mounted (await! (support/create-root! element))]
              (reset! root mounted)
              (await! (support/render! mounted
                        [presentation/<comment-markdown>
                         "**Comment**\n\n![Uploaded image](/img/tolgrav.png)\n\n<iframe src='/private-library'></iframe>\n\n<Component component='website.components.content.markdown/<image>' src='/injected-component.png' />"
                         {:trust :trusted}]))
              (await! (support/wait-for! #(.querySelector element "strong")))
              (is (= "Comment" (.-textContent (.querySelector element "strong"))))
              (is (nil? (.querySelector element "iframe")))
              (is (nil? (.querySelector element "[src='/injected-component.png']")))
              (is (.querySelector element "img[alt='Uploaded image']") "Ordinary uploaded Markdown images remain available")))
          (.catch #(is false (str % " DOM: " (.-innerHTML element))))
          (.finally (fn []
                      (when @root (support/unmount! @root))
                      (.remove element)
                      (restore-db!)
                      (done)))))))
