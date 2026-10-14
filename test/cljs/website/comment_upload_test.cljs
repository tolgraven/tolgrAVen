(ns website.comment-upload-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.test-support :as support]
            [website.events]
            [website.subs]
            [website.loader :as loader]
            [website.modules.blog.events]
            [website.modules.blog.subs]
            [website.modules.blog.comment-views :as comment-views]
            [website.modules.markdown-editor.events]
            [website.supabase.runtime :as client]))

(def asset
  {:id "comment-preview-fixture"
   :src "/img/tolgrav-square.png"
   :width 800
   :height 600
   :renditions {:original {:src "/img/tolgrav-square.png"
                           :width 800
                           :height 600
                           :bytes 1024
                           :mime "image/png"}
                :webp [{:src "/img/tolgrav-square.webp", :width 800, :height 600, :bytes 512, :mime "image/webp"}]
                :avif [{:src "/img/tolgrav-square.avif", :width 800, :height 600, :bytes 256, :mime "image/avif"}]}})

(defn transfer! [element kind file]
  (let [event (js/Event. kind #js {:bubbles true, :cancelable true})
        transfer (js/DataTransfer.)]
    (.add (.-items transfer) file)
    (js/Object.defineProperty event (if (= kind "paste") "clipboardData" "dataTransfer") #js {:value transfer})
    (.dispatchEvent element event)))

(defn restore-fixture-db! [restore-db!]
  ;; Keep actual acquired code/styles paired with the native runtime cache.
  (let [current @r/*app-db]
    (restore-db!)
    (r/dispatch-sync [:set [:loader] (:loader current)])
    (r/dispatch-sync [:set [:state :init :scope] (get-in current [:state :init :scope])])))

(deftest comment-upload-notices-exit-after-the-actual-comment-preview-paints
     (async done
       (let [before (r/make-restore-fn)
             original client/authenticated-request!
             success (atom nil)
             root (atom nil)
             element (.createElement js/document "div")
             path [42 "113"]
             src "/img/tolgrav-square.png"
             ready-asset asset]
         (.appendChild (.-body js/document) element)
         (set! client/authenticated-request! (fn [& [_ _ _ received]] (reset! success received)))
         (-> (go-promise
               ;; Restore fixtures and native acquisition promises are separate
               ;; lifecycles. Reacquire actual code against this fresh test state.
               (r/dispatch-sync [:init/app-db])
               (doseq [module [:markdown :markdown-editor :user]]
                 (swap! loader/*loads dissoc module)
                 (swap! loader/*code-loads dissoc module)
                 (await! (loader/acquire-code! module)))
               (r/dispatch-sync [:form-field [:write-comment path :text] "Comment preview"])
               (let [mounted (await! (support/create-root! element))]
                 (reset! root mounted)
                 (await! (support/render! mounted
                           [:section {:style {:position "fixed", :top 0, :left 0, :width "30rem", :z-index 10}}
                            [comment-views/<comment-editor> {:parent-path path}]]))
                 (await! (support/wait-for! #(.querySelector element ".md-editor-content p")))
                 (let [editable (.querySelector element ".md-editor-content")
                       range (.createRange js/document)
                       selection (.getSelection js/window)]
                   (.focus editable)
                   (.selectNodeContents range editable)
                   (.collapse range false)
                   (.removeAllRanges selection)
                   (.addRange selection range)
                   (.dispatchEvent js/document (js/Event. "selectionchange"))
                   (await! (support/settle!))
                   (transfer! editable "paste" (js/File. #js ["image"] "comment-preview.png" #js {:type "image/png"}))
                   (await! (support/wait-for! #(some? @success)))
                   (@success {:id "comment-preview-job", :status "ready", :asset ready-asset})
                   (await! (support/wait-for! #(some :preview-ready? (vals (get-in @r/*app-db [:state :blog :image-uploads])))))
                   (await! (support/wait-for! #(nil? (.querySelector element ".upload-row"))))
                   (let [image (.querySelector element ".blog-comment-preview img, .blog-comment-text img")]
                     (is image "The actual comment preview paints the uploaded image")
                     (is (pos? (.-naturalWidth image)))
                     (is (nil? (.querySelector element ".upload-row")))
                     (is (not-any? :preview-error (vals (get-in @r/*app-db [:state :blog :image-uploads]))))))))
             (.catch #(is false (str % " DOM: " (.-innerHTML element))))
             (.finally (fn []
                         (when @root (support/unmount! @root))
                         (set! client/authenticated-request! original)
                         (.remove element)
                         (restore-fixture-db! before)
                         (done)))))))
