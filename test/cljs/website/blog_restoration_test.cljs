(ns website.blog-restoration-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [website.test-support :as support]
            [cljs.reader :as reader]
            [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.test-support :refer [mount-subscriptions!]]




            [clojure.string :as string]
            [website.modules.blog.cache :as cache]
            [website.modules.blog.comments :as comments]
            [website.ssr.contract :as contract]
            [website.modules.blog.views :as blog]
            [website.modules.blog.comment-views :as comment-views]
            [website.component.storage :as storage]
            [website.component.storage-disk :as heavy-disk]
            [website.component.restore :as restore]
            [website.supabase.scoped :as scoped]
            [website.modules.user.views :as user]
            [website.modules.user.module :as user-module]
            [website.loader :as loader]
            [website.loader.style-catalog :as style-catalog]
            [shadow.lazy :as lazy]
            [website.modules.user.events]
            [website.events]
            [website.subs]))
(deftest restore-only-public-queries-and-retain-newer-server-data
  (let [before @r/*app-db
        buckets @storage/*buckets
        query (scoped/query-key {:path-collection [:blog-comments], :scoped? true})
        path [:store :scoped query]
        fold [:state :blog :comment-thread-expanded]
        snapshots {[:state path] {:value {:docs [{:id "cached"}]}},
                   [:state fold] {:value {[28 "root"] false}}}]
    (try (reset! r/*app-db {})
         (swap! storage/*buckets assoc :public snapshots)
         (r/with-framework-bindings [storage/read! (fn [id _] (get snapshots id))
                       storage/track! (fn [& _])
                       storage/schedule! (fn [])]
           (cache/restore!)
           (is (= {:docs [{:id "cached"}]} (get-in @r/*app-db path)))
           (is (false? (get-in @r/*app-db (conj fold [28 "root"]))))
           (swap! r/*app-db assoc-in path {:docs [{:id "fresh-server"}]})
           (cache/restore!)
           (is (= "fresh-server" (get-in @r/*app-db (into path [:docs 0 :id]))))
           (reset! r/*app-db (contract/snapshot-state {:kind :blog, :posts []}))
           (cache/restore!)
           (is (= {[28 "root"] false} (get-in @r/*app-db [:state :blog :comment-thread-expanded]))
               "After hydration saved display choices replace server defaults")
           (cache/stop!))
         (is (false? (boolean (cache/public-query? (pr-str {:scoped? true,
                                                            :path-collection [:gpt]})))))
         (is (false? (boolean (cache/public-query? "malformed ["))))
         (finally (cache/stop!) (reset! r/*app-db before) (reset! storage/*buckets buckets)))))
(deftest cache-tracks-public-query-events-and-releases-its-subscription
  (let [before @r/*app-db
        *scheduled (atom 0)
        public-key (scoped/query-key (comments/root-query 42 10))
        private-key (scoped/query-key {:path-collection [:gpt], :scoped? true})
        path [:store :scoped public-key]]
    (r/with-framework-bindings [storage/*tracked (atom {})
                  storage/ready! (fn ([] (js/Promise.resolve nil))
                                    ([_] (js/Promise.resolve nil)))
                  storage/schedule! #(swap! *scheduled inc)]
      (try
        (cache/start!)
        (r/dispatch-sync [:store/scoped public-key {:docs [{:id "first"}]}])
        (r/flush)
        (is (= {:docs [{:id "first"}]}
               ((:read (get @storage/*tracked [:state path])))))
        (r/dispatch-sync [:store/scoped public-key {:docs [{:id "updated"}]}])
        (r/dispatch-sync [:store/scoped private-key {:docs [{:id "private"}]}])
        (r/flush)
        (is (= "updated" (get-in ((:read (get @storage/*tracked [:state path]))) [:docs 0 :id])))
        (is (not (contains? @storage/*tracked [:state [:store :scoped private-key]])))
        (is (pos? @*scheduled))
        (cache/stop!)
        (is (empty? @storage/*tracked))
        (let [scheduled @*scheduled]
          (r/dispatch-sync [:store/scoped public-key {:docs []}])
          (r/flush)
          (is (= scheduled @*scheduled) "Disposed cache no longer schedules persistence"))
        (finally (cache/stop!) (reset! r/*app-db before))))))

(deftest returning-comments-render-immediately-and-keep-folded-threads-folded
  (async
    done
    (->
      (go-promise
        (let [element (.createElement js/document "div")
              root (await! (support/create-root! element))
              before @restore/*context
              original (r/subscribe-function)
              author {:id "author", :name "Writer"}
              comment (fn [id]
                        {:id id,
                         :title (str "Comment " id),
                         :user author,
                         :text "Restored body",
                         :ts 1,
                         :reply-count 1})
              roots (r/atom (into {}
                                  (map #(vector %
                                                (comment
                                                  %)))
                                  (range 5)))
              replies (r/atom {"reply" (comment
                                         "reply")})]
          (try (restore/begin! {:back? true})
               (r/with-framework-bindings [loader/modules
                               {:user (reify
                                        lazy/ILoadable
                                          (ready? [_] true)
                                        IDeref
                                          (-deref [_] (dissoc user-module/spec :content :depends)))}
                             r/subscribe
                               (fn
                                 ([q]
                                  (case (first q)
                                    :comments/root-page
                                      (r/atom {:records @roots, :more? false, :loading? false})
                                    :comments/for-q-flat (if (= 2 (count q)) roots replies)
                                    :comments/thread-expanded? (r/atom false)
                                    :blog/state (r/atom false)
                                    :user/active-user (r/atom nil)
                                    :comments/adding? (r/atom false)
                                    (original q)))
                                 ([q _] (original q)))]
                 (await! (support/render! root [comment-views/<comments-section> {:post {:id 28}}]))
                 (is (= 5 (.-length (.querySelectorAll element ".blog-comment-title")))
                     "Restored roots are visible in the first commit")
                 (is (.contains (.-classList (.querySelector element ".blog-comments")) "appeared"))
                 (is (zero? (.-length (.querySelectorAll element ".appear-wrapper:not(.appeared)")))
                     "Restored comments have no hidden entrance frame")
                 (is (= 5
                        (.-length (.querySelectorAll element
                                                     ".blog-comment-collapsed-placeholder"))))
                 (is (not (.includes (.-textContent element) "Comment reply"))))
               (finally (support/unmount! root) (reset! restore/*context before)))))
      (.catch (fn [error] (is false (str error))))
      (.finally done))))
(deftest user-panel-closes-state-immediately-and-component-owns-exit
  (async
    done
    (->
      (go-promise
        (let [element (.createElement js/document "div")
              root (await! (support/create-root! element))
              before @r/*app-db
              context @restore/*context]
          (.appendChild (.-body js/document) element)
          (restore/begin! {})
          (r/dispatch-sync [:user/close-ui])
          (await! (support/render! root [user/<user-section>]))
          (is (nil? (.querySelector element ".user-section-wrapper")))
          (r/dispatch-sync [:user/open-ui :login])
          (r/flush)
          (is (some? (.querySelector element ".user-section-wrapper")))
          (r/dispatch-sync [:user/close-ui])
          (r/flush)
          (is (= [:closed] (get-in @r/*app-db [:state :user-section])))
          (is (some? (.querySelector element ".user-section-wrapper.exiting")))
          ;; Reopening before exit completion must retain the same mounted wrapper.
          (let [panel (.querySelector element ".user-section-wrapper")]
            (r/dispatch-sync [:user/open-ui :login])
            (r/flush)
            (is (identical? panel (.querySelector element ".user-section-wrapper"))))
          (r/dispatch-sync [:user/close-ui])
          (r/flush)
          (js/setTimeout (fn []
                           (r/flush)
                           (is (nil? (.querySelector element ".user-section-wrapper"))
                               "Exited DOM is removed")
                           (support/unmount! root)
                           (.remove element)
                           (reset! r/*app-db before)
                           (reset! restore/*context context)
                           (done))
                         250)))
      (.catch (fn [error] (is false (str error)) (done))))))
(deftest avatar-does-not-overlay-a-placeholder-while-loading
  (async
    done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                *profile (r/atom nil)
                before @r/*app-db
                fallback "/img/avatar-fallback-test.png"
                avatar "https://example.invalid/author-avatar.png"
                render! #(do (r/flush))]
            (try (swap! r/*app-db assoc-in [:content :common :user-avatar-fallback] fallback)
                 (await! (support/render! root
                                          [(fn [] [user/<user-avatar> @*profile
                                                   "blog-user-avatar"])]))
                 (let [image (.querySelector element "img")]
                   (is (= "hidden" (.. image -style -visibility)))
                   (is (nil? (.getAttribute image "src"))
                       "Pending profiles do not request the default logo"))
                 (reset! *profile {:name "Author", :avatar avatar})
                 (render!)
                 (is (= 1 (.-length (.querySelectorAll element "img")))
                     "No fallback layer before image load")
                 (is (= avatar (.getAttribute (.querySelector element "img") "src")))
                 (.dispatchEvent (.querySelector element "img") (js/Event. "error"))
                 (render!)
                 (is (= fallback (.getAttribute (.querySelector element "img") "src"))
                     "Real failures retain a fallback")
                 (reset! *profile {:name "Other author",
                                   :avatar "https://example.invalid/other-avatar.png"})
                 (render!)
                 (is (= "https://example.invalid/other-avatar.png"
                        (.getAttribute (.querySelector element "img") "src"))
                     "An earlier failure does not poison a different avatar")
                 (reset! *profile {:name "No avatar"})
                 (render!)
                 (is (= fallback (.getAttribute (.querySelector element "img") "src")))
                 (finally (support/unmount! root) (reset! r/*app-db before)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest hydration-recovers-failed-avatar-originals-without-widening-the-avatar
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                stylesheets (mapv (fn [path] [path (.createElement js/document "link")])
                                  (into ["/css/website/main.min.css"]
                                        (style-catalog/paths
                                          (into {} (map (fn [[id spec]] [id (:paths spec)]))
                                                style-catalog/modules)
                                          :blog)))
                before @r/*app-db
                fallback "/img/tolgrav-square.png"
                converted-stem (str "/storage/v1/object/public/avatars/test/"
                                    (apply str (repeat 64 "0")))
                converted-avatar (str (.-origin js/location) converted-stem ".png")]
            (.appendChild (.-body js/document) element)
            (try
              (swap! r/*app-db assoc-in [:content :common :user-avatar-fallback] fallback)
              (await! (js/Promise.all
                        (into-array
                          (map (fn [[path stylesheet]]
                                 (js/Promise.
                                   (fn [resolve reject]
                                     (set! (.-rel stylesheet) "stylesheet")
                                     (set! (.-href stylesheet) path)
                                     (set! (.-onload stylesheet) #(resolve nil))
                                     (set! (.-onerror stylesheet)
                                           #(reject (js/Error. "Avatar CSS unavailable")))
                                     (.appendChild (.-head js/document) stylesheet))))
                               stylesheets))))
              (doseq [[avatar selected expected]
                      [["/missing-avatar.svg" "/missing-avatar.svg" fallback]
                       ["/missing-avatar.png" "/missing-avatar.png" fallback]
                       ["/img/tolgrav.png" "/img/tolgrav.avif" "/img/tolgrav.png"]
                       [converted-avatar (str converted-stem ".avif") converted-avatar]]]
                (let [form [user/<user-avatar>
                            {:name "A very long author name that must never widen the avatar"
                             :avatar avatar} "blog-user-avatar"]]
                  ;; Model an SSR request which already failed before hydration;
                  ;; no later error event is available to the newly attached handler.
                  (set! (.-innerHTML element) (r/render-to-string form))
                  (let [image (.querySelector element "img")]
                    (doseq [[property value] [["complete" true] ["naturalWidth" 0]
                                             ["currentSrc" (str (.-origin js/location) selected)]]]
                      (js/Object.defineProperty image property #js {:configurable true :value value}))
                    (let [bounds (.getBoundingClientRect image)]
                      (is (< (js/Math.abs (- (.-width bounds) (.-height bounds))) 1)
                          "Broken-image alt text stays in the square avatar box before hydration"))
                    (let [root (r/hydrate-root element form)]
                      (try
                        (await! (support/wait-for!
                                  #(and (= expected (.getAttribute (.querySelector element "img") "src"))
                                        (or (not= expected avatar)
                                            (zero? (.-length (.querySelectorAll element "source")))))))
                        (is (= expected (.getAttribute (.querySelector element "img") "src"))
                            "Failed originals use the logo; failed modern sources still try the original")
                        (is (= 1 (.-length (.querySelectorAll element "img"))))
                        (finally (r/unmount root)))))))
              (finally
                (doseq [[_ stylesheet] stylesheets] (.remove stylesheet))
                (.remove element)
                (reset! r/*app-db before)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest load-more-retains-the-visible-window-and-persists-the-requested-size
  (async
    done
    (-> (go-promise
          (let [before @r/*app-db
                rows (fn [n]
                       {:docs (mapv #(hash-map :id (str %)
                                               :data {:id (str %), :ts %, :parent-post 42})
                                (range n))})]
            (r/clear-subscription-cache!)
            (try (reset! r/*app-db {})
                 (r/dispatch-sync [:store/scoped (scoped/query-key (comments/root-query 42 10))
                                    (rows 11)])
                 (let [{:keys [values unmount!]} (await! (mount-subscriptions!
                                                           {:page [:comments/root-page 42]}))
                       page #(get (values) :page)]
                   (try (is (= 10 (count (:records (page)))))
                        (is (:more? (page)))
                        (r/dispatch-sync [:blog/load-more-comments 42])
                        (await! (support/settle!))
                        (is (= 20 (get-in @r/*app-db [:state :blog :comment-limit 42])))
                        (is (= 10 (count (:records (page))))
                            "Keep existing comments during the next read")
                        (is (:loading? (page)))
                        (r/dispatch-sync
                          [:store/scoped (scoped/query-key (comments/root-query 42 20)) (rows 21)])
                        (await! (support/settle!))
                        (is (= 20 (count (:records (page)))))
                        (is (false? (:loading? (page))))
                        (is (some #{[:state :blog :comment-limit]} cache/state-paths))
                        (finally (unmount!))))
                 (finally (r/clear-subscription-cache!) (reset! r/*app-db before)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
(deftest saved-comment-window-folds-and-data-round-trip-through-split-storage
  (async done
    (-> (go-promise
          (let [before @r/*app-db
                path [:store :scoped (scoped/query-key (comments/root-query 42 20))]
                values {path {:docs (mapv #(hash-map :id (str %) :data {:id (str %), :ts %}) (range 21))},
                        [:state :blog :comment-limit] {42 20},
                        [:state :blog :comment-thread-expanded] {[42 "root"] false,
                                                                 [42 "other" "child"] true}}
                disk (atom nil)
                heavy (atom {})
                writes (atom 0)
                marker (atom nil)]
            (r/with-framework-bindings
              [storage/*buckets (atom {})
               storage/*tracked (atom {})
               storage/*reads (atom {:public (js/Promise.resolve nil)})
               storage/*ready (atom #{:public})
               storage/*heavy-ready (atom #{:public})
               storage/*dirty (atom #{})
               storage/*consumed (atom {})
               storage/*pending (atom nil)
               storage/*write-tick (atom nil)
               storage/*public-saved? (atom false)
               storage/*heavy-owners (atom #{})
               storage/*migrating (atom #{})
               storage/*disk-writes (atom {})
               storage/*disk-generations (atom {})
               heavy-disk/write! (fn [_ bucket] (reset! heavy bucket) (js/Promise.resolve nil))
               storage/write-disk! (fn [_ value] (swap! writes inc) (reset! disk value))
               storage/mark-return! #(reset! marker %)]
              (try
                (reset! r/*app-db {})
                (doseq [[path value] values]
                  (r/dispatch-sync [:component-data/install path value])
                  (storage/track! [:state path] #(get-in @r/*app-db path storage/missing) cache/options))
                (storage/track! :public-content (constantly {:common {:title "Cached"}}) cache/options)
                (storage/save-navigation!)
                (await! (storage/drain-async!))
                (is (= 1 @writes) "Small state uses one localStorage write")
                (is (true? @marker))
                (let [saved (merge @heavy (reader/read-string @disk))]
                  (reset! r/*app-db {})
                  (reset! storage/*buckets {:public saved})
                  (cache/restore!)
                  (is (= 20 (get-in @r/*app-db [:state :blog :comment-limit 42])))
                  (is (false? (get-in @r/*app-db [:state :blog :comment-thread-expanded [42 "root"]])))
                  (is (true? (get-in @r/*app-db
                                     [:state :blog :comment-thread-expanded [42 "other" "child"]])))
                  (is (= 21 (count (:docs (get-in @r/*app-db path)))))
                  (storage/read! :public-content cache/options)
                  (await! (storage/drain-async!))
                  (is (= {} (merge @heavy (reader/read-string @disk))) "Restored disk entries are consumed")
                  (storage/save-navigation!)
                  (await! (storage/drain-async!))
                  (is (contains? (merge @heavy (reader/read-string @disk)) [:state path])
                      "Navigation republishes the restored content")
                  (r/with-framework-bindings [storage/write-disk! (fn [& _] (throw (js/Error. "Full")))]
                    (swap! storage/*dirty conj :public)
                    (storage/save-navigation!)
                    (await! (storage/drain-async!))
                    (is (false? @marker) "Failed writes must not opt out of SSR")))
                (finally (cache/stop!) (reset! storage/*tracked {}) (storage/drain!) (reset! r/*app-db before))))))
        (.catch #(is false (str %)))
        (.finally done))))
(deftest folded-reply-placeholder-reserves-its-height-before-mount
  (let [context @restore/*context]
    (try (restore/begin! {:hydrate? true})
         (let [html (r/render-to-string [comment-views/<collapsed-reply-view>
                                              {:path [27 1], :reply-count 2}])]
           (is (string/includes? html "max-height:3rem"))
           (is (string/includes? html "hidden replies")))
         (finally (reset! restore/*context context)))))
