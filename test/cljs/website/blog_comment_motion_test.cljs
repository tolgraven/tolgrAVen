(ns website.blog-comment-motion-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.component.restore :as restore]
            [website.loader.style-catalog :as style-catalog]
            [website.modules.blog.views :as blog]
            [website.test-support :as support]))

(defn- tick! [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- heights [element]
  (mapv #(.-height (.getBoundingClientRect %))
        (array-seq (.querySelectorAll element ".blog-comment-reply-row"))))

(defn- sample! [element duration]
  (js/Promise.
    (fn [resolve _]
      (let [start (js/performance.now)]
        (letfn [(frame! [samples]
                  (let [elapsed (- (js/performance.now) start)
                        samples' (conj samples {:time elapsed, :heights (heights element)})]
                    (if (> elapsed duration)
                      (resolve samples')
                      (js/requestAnimationFrame #(frame! samples')))))]
          (js/requestAnimationFrame #(frame! [])))))))

(defn- fixture! []
  (go-promise
    (let [element (.createElement js/document "div")
          root (await! (support/create-root! element))
          paths (into ["/css/website/main.min.css"]
                      (style-catalog/paths
                        (into {} (map (fn [[id spec]] [id (:paths spec)])) style-catalog/modules)
                        :blog))
          links (mapv (fn [_] (.createElement js/document "link")) paths)]
      (set! (.-cssText (.-style element)) "position:fixed;top:2rem;left:2rem;width:30rem;z-index:10000")
      (.appendChild (.-body js/document) element)
      (try
        (await! (js/Promise.all
                  (into-array
                    (map (fn [path link]
                           (js/Promise.
                             (fn [resolve reject]
                               (set! (.-rel link) "stylesheet")
                               (set! (.-href link) path)
                               (set! (.-onload link) #(resolve nil))
                               (set! (.-onerror link) #(reject (js/Error. (str "Missing motion CSS: " path))))
                               (.appendChild (.-head js/document) link))))
                         paths links))))
        {:element element
         :root root
         :close! #(do (support/unmount! root)
                      (.remove element)
                      (doseq [link links] (.remove link)))}
        (catch :default error
          (support/unmount! root)
          (.remove element)
          (doseq [link links] (.remove link))
          (throw error))))))

(defn- <rows> [*rows *expanded?]
  (let [expanded? @*expanded?]
  [:section.blog-comments
   [:div.blog-comments-inner
    [:div.blog-comment-reply {:style {"--comment-reveal-duration" "200ms"}}
     (for [index @*rows]
       ^{:key index}
       [blog/<reply-row>
        {:expanded? expanded?
         :appear {:class "reply-height", :settle-offscreen? true, :restore? false}
         :props {:style {"--comment-reveal-delay" (str (* 48 index) "ms")}}
         :children [[:div {:style {:height "4rem"}} (str "Reply " index)]]}])]]]))

(deftest late-replies-grow-layout-in-their-entrance-order-and-reverse-on-reentry
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))
                *rows (r/atom [])
                *expanded? (r/atom true)
                context @restore/*context]
            (try
              (restore/begin! {})
              (await! (support/render! root [<rows> *rows *expanded?]))
              ;; Replies arrive after the old parent max-height would finish.
              (await! (tick! 220))
              (reset! *rows [0 1 2])
              (r/flush)
              (let [samples (await! (sample! element 420))
                    final (last (heights element))
                    started (mapv (fn [index]
                                    (:time (first (filter #(> (nth (:heights %) index 0) 1) samples))))
                                  (range 3))]
                (is (every? number? started))
                (is (apply < started) "Each reply starts growing after the preceding reply")
                (is (some #(let [height (first (:heights %))]
                             (< 1 height (- final 1))) samples)
                    "The first arriving reply has intermediate layout heights, not a size jump")
                (is (every? #(<= (js/Math.abs (- final %)) 1) (heights element))))
              (let [row (.querySelector element ".blog-comment-reply-row")]
                (reset! *expanded? false)
                (r/flush)
                (await! (support/settle!))
                (is (.-inert row) "Closing replies stop accepting focus immediately")
                (await! (tick! 75))
                (reset! *expanded? true)
                (r/flush)
                (is (identical? row (.querySelector element ".blog-comment-reply-row")))
                (await! (tick! 400))
                (is (every? pos? (heights element)))
                (reset! *expanded? false)
                (r/flush)
                (await! (support/settle!))
                (await! (tick! 210))
                (is (every? #(< % 1) (heights element)) "Collapse finishes within comment retention"))
              (finally (close!) (reset! restore/*context context)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest restored-and-offscreen-layout-rows-keep-natural-height
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))
                context @restore/*context
                form (fn [appear props]
                       [:section.blog-comments
                        [:div.blog-comments-inner
                         [blog/<reply-row>
                          {:expanded? true
                           :appear appear
                           :props props
                           :children [[:div {:style {:height "4rem"}} "Restored reply"]]}]]])]
            (try
              (restore/begin! {:back? true})
              (await! (support/render! root (form {:class "reply-height", :settle-offscreen? true} {})))
              (is (every? pos? (heights element)) "Restored rows are laid out on the first commit")
              (is (zero? (.-length (.querySelectorAll element ".reply-height:not(.appeared)"))))
              (await! (support/render! root nil))
              (restore/begin! {})
              (await! (support/render! root
                                      (form {:class "reply-height", :settle-offscreen? true}
                                            {:style {:position "fixed", :top "200vh"}})))
              (let [body (.querySelector element ".blog-comment-reply-body > div")
                    natural (.-height (.getBoundingClientRect body))]
                (is (every? #(<= (js/Math.abs (- natural %)) 1) (heights element))
                    "Offscreen rows retain their full natural height on the first commit"))
              (is (zero? (.-length (.querySelectorAll element ".reply-height:not(.appeared)"))))
              (let [row (.querySelector element ".blog-comment-reply-row")]
                (is (zero? (.-length (.getAnimations row))) "Offscreen entrance schedules no animation"))
              (finally (close!) (reset! restore/*context context)))))
        (.catch #(is false (str %)))
        (.finally done))))
