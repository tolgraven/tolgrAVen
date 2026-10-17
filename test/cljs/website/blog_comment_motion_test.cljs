(ns website.blog-comment-motion-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.component.restore :as restore]
            [website.loader.style-catalog :as style-catalog]
            [website.modules.blog.views :as blog]
            [website.modules.blog.comment-views :as comment-views]
            [website.test-support :as support]
            [website.macros :refer-macros [defc]]))

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
                        placeholder (.querySelector element ".blog-comment-collapsed-placeholder")
                        placeholder-root (some-> placeholder (.closest ".blog-comment-reply"))
                        samples' (conj samples {:time elapsed
                                                :heights (heights element)
                                                :placeholder? (some? placeholder)
                                                :placeholder-exiting? (boolean (some-> placeholder-root .-classList (.contains "exiting")))
                                                :native-exit? (boolean
                                                                (when placeholder-root
                                                                  (some #(and (= "running" (.-playState %))
                                                                              (contains? #{"opacity" "transform"} (.-transitionProperty %)))
                                                                        (array-seq (.getAnimations placeholder-root)))))
                                                :outer (some-> (.querySelector element ".blog-comment-reply-outer")
                                                               .getBoundingClientRect .-height)})]
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
       [comment-views/<reply-row>
        {:expanded? expanded?
         :appear {:class "reply-height", :settle-offscreen? true, :restore? false}
         :props {:style {"--comment-reveal-delay" (str (* 48 index) "ms")}}
         :children [[:div {:style {:height "4rem"}} (str "Reply " index)]]}])]]]))

(defn- <thread-handoff> [*ready? *expanded? & [delay-ms]]
  [:section.blog-comments
   [:div.blog-comments-inner
    [comment-views/<reply-thread>
     [^{:key :replies}
      [:div.blog-comment-reply
       (when @*ready?
         [comment-views/<reply-row>
          {:expanded? @*expanded?
           :appear {:class "reply-height", :settle-offscreen? true, :restore? false}
           :props {:style {"--comment-reveal-delay" (str (or delay-ms 0) "ms")}}
           :children [[:div {:style {:height "4rem"}} "Arriving reply"]]}])]
      (when (or (not @*ready?) (not @*expanded?))
        ^{:key :collapsed}
        [comment-views/<collapsed-reply-view>
         {:path [42 "root"], :reply-count 1, :loading? (not @*ready?)}])]]]])

(deftest arriving-and-cached-replies-retain-the-collapsed-footprint
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))
                context @restore/*context
                *ready? (r/atom false)
                *expanded? (r/atom true)]
            (try
              (restore/begin! {})
              (await! (support/render! root [<thread-handoff> *ready? *expanded?]))
              (await! (tick! 550))
              (let [outer (.querySelector element ".blog-comment-reply-outer")
                    initial (.-height (.getBoundingClientRect outer))]
                (is (pos? initial))
                (reset! *ready? true)
                (r/flush)
                (let [samples (await! (sample! element 420))]
                  (is (every? #(>= (:outer %) (- initial 0.5)) samples)
                      "Removing the loading placeholder cannot expose a zero-height first reply frame")
                  (is (some #(< initial (:outer %) (- (:outer (last samples)) 1)) samples)
                      "The container grows beyond the placeholder through intermediate heights"))
                (is (nil? (.querySelector element ".blog-comment-collapsed-placeholder")))
                (reset! *expanded? false)
                (r/flush)
                (await! (tick! 550))
                (let [row (.querySelector element ".blog-comment-reply-row")]
                  (reset! *expanded? true)
                  (r/flush)
                  (let [samples (await! (sample! element 420))]
                    (is (every? #(>= (:outer %) (- initial 0.5)) samples)))
                  (is (identical? row (.querySelector element ".blog-comment-reply-row")))))
              (finally (close!) (reset! restore/*context context)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest arriving-replies-retain-a-placeholder-during-a-shortened-reverse
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))
                context @restore/*context
                *ready? (r/atom false)
                *expanded? (r/atom true)]
            (try
              (restore/begin! {})
              (await! (support/render! root [<thread-handoff> *ready? *expanded? 48]))
              (let [outer (.querySelector element ".blog-comment-reply-outer")
                    placeholder (.closest (.querySelector outer ".blog-comment-collapsed-placeholder")
                                          ".blog-comment-reply")
                    initial (.-height (.getBoundingClientRect outer))]
                ;; Reverse at the first running entrance frame, as a nested
                ;; cached thread can do. Native CSS shortens the reversed exit.
                (loop [attempt 0]
                  (when (and (< attempt 30) (empty? (array-seq (.getAnimations placeholder))))
                    (await! (js/Promise. js/requestAnimationFrame))
                    (recur (inc attempt))))
                (is (some #(= "running" (.-playState %)) (array-seq (.getAnimations placeholder)))
                    "The placeholder has an actual unfinished native entrance")
                (reset! *ready? true)
                (r/flush)
                (let [samples (await! (sample! element 420))]
                  (is (every? #(>= (:outer %) (- initial 0.5)) samples)
                      "A shortened reverse cannot remove the initial footprint before the arriving reply grows")
                  (is (some #(< initial (:outer %) (- (:outer (last samples)) 1)) samples)
                      "The native reply still grows through intermediate heights"))
                (is (nil? (.querySelector outer ".blog-comment-collapsed-placeholder"))))
              (finally (close!) (reset! restore/*context context)))))
        (.catch #(is false (str %)))
        (.finally done))))

(defc <automatic-thread-handoff> [*ready? *expanded?]
  ;; Nested expanded threads can receive cached data before their placeholder's
  ;; first entrance frame; this is an ordinary mounted lifecycle, not a fake clock.
  (r/use-layout-effect (fn [] (reset! *ready? true) nil) [])
  [<thread-handoff> *ready? *expanded?])

(deftest automatic-replies-retain-a-placeholder-that-never-started-entering
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))
                context @restore/*context
                *ready? (r/atom false)
                *expanded? (r/atom true)]
            (try
              (restore/begin! {})
              (let [captured (sample! element 500)]
                (await! (support/render! root [<automatic-thread-handoff> *ready? *expanded?]))
                (let [samples (await! captured)
                      started (drop-while #(not (:placeholder? %)) samples)
                      initial (:outer (first started))]
                  (is (and initial (pos? initial)) "Observe the real initial placeholder footprint")
                  (when initial
                    (is (every? #(>= (:outer %) (- initial 0.5)) started)
                        "An unstarted entrance cannot retire its layout before the first reply grows"))
                  (is (some #(and (:placeholder? %) (:placeholder-exiting? %) (not (:native-exit? %))) samples)
                      "The native placeholder exits without creating a transition from its already-hidden styles")
                  (is (some #(< 1 (first (:heights %)) (- (first (:heights (last samples))) 1))
                            (filter #(seq (:heights %)) samples))
                      "The reply still grows through intermediate heights")
                  (is (nil? (.querySelector element ".blog-comment-collapsed-placeholder")))))
              (finally (close!) (reset! restore/*context context)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest hydrated-placeholder-retires-its-document-clock-before-native-exit
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture!))
                context @restore/*context
                *ready? (r/atom false)
                *expanded? (r/atom true)]
            (try
              ;; Exercise network SSR entrance policy, unlike the SPA handoff
              ;; above. Keep its real completed CSS clocks before exit.
              (restore/begin! {:hydrate? true})
              (await! (support/render! root [<thread-handoff> *ready? *expanded?]))
              (let [outer (.querySelector element ".blog-comment-reply-outer")
                    placeholder (.closest (.querySelector outer ".blog-comment-collapsed-placeholder")
                                          ".blog-comment-reply")
                    entrance (vec (array-seq (.getAnimations placeholder)))]
                (is (= "true" (.getAttribute placeholder "data-document-appear")))
                (is (= #{"document-opacity" "document-zoom-y"}
                       (set (map #(.-animationName %) entrance)))
                    "The native styles supply both original SSR entrance clocks")
                (await! (js/Promise.allSettled (into-array (map #(.-finished %) entrance))))
                (is (every? #(= "finished" (.-playState %)) entrance))
                (restore/hydrated!)
                (await! (support/settle!))
                (is (identical? placeholder
                                (.closest (.querySelector outer ".blog-comment-collapsed-placeholder")
                                          ".blog-comment-reply")))
                (is (= "true" (.getAttribute placeholder "data-document-appear"))
                    "Completing hydration retains the same settled SSR clock")
                (let [initial (.-height (.getBoundingClientRect outer))]
                  (is (pos? initial))
                  (reset! *ready? true)
                  (r/flush)
                  (is (.contains outer placeholder) "Presence retains the actual placeholder on exit")
                  (is (= "true" (.getAttribute placeholder "aria-hidden")))
                  (is (nil? (.getAttribute placeholder "data-document-appear"))
                      "An exiting root cannot retain the fill-both SSR entrance")
                  (let [samples (await! (sample! element 420))]
                    (is (some #(and (:placeholder-exiting? %) (:native-exit? %)) samples)
                        "Native exit transitions follow the committed release of the document clock")
                    (is (every? #(>= (:outer %) (- initial 0.5)) samples)
                        "Finished SSR clocks cannot remove the placeholder before the reply grows")
                    (is (some #(and (:placeholder? %) (< (nth (:heights %) 0 0) 1)) samples)
                        "The placeholder survives the reply's initial zero-height frames")
                    (is (every? #(or (>= (nth (:heights %) 0 0) (- initial 0.5))
                                    (:placeholder? %)) samples)
                        "Only a reply that covers the collapsed footprint can outlive the placeholder")
                    (is (some #(< initial (:outer %) (- (:outer (last samples)) 1)) samples)
                        "The handoff includes continuous intermediate container heights"))
                  (is (not (.contains outer placeholder)))
                  (is (nil? (.querySelector outer ".blog-comment-collapsed-placeholder")))))
              (finally (close!) (reset! restore/*context context)))))
        (.catch #(is false (str %)))
        (.finally done))))

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
                         [comment-views/<reply-row>
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
