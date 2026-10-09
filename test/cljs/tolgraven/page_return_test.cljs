(ns tolgraven.page-return-test
  "Mounted restoration tests use real events/subscriptions and the same renderer
   as local documents. Shadow code splitting is the only adapted boundary."
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [reagent.core :as r]
            [reagent.dom.client :as dom]
            [shadow.lazy :as lazy]
            [re-frame.core :as re-frame]
            [tolgraven.react :as rf]
            [tolgraven.test-support :as support]
            [tolgraven.modules.blog.views :as blog]
            [tolgraven.modules.blog.comments :as comments]
            [tolgraven.modules.user.module :as user]
            [tolgraven.loader :as loader]
            [tolgraven.component.restore :as restore]
            [tolgraven.render-context :as context]
            [tolgraven.browser-resources :as browser-resources]
            [tolgraven.supabase.scoped :as scoped]
            [tolgraven.ssr.return-contract :as contract]
            [tolgraven.ssr.render :as render]
            [tolgraven.ssr.local :as local]
            [tolgraven.ssr.return-worker :as worker]))

(r/defc <check> [form committed!]
  (rf/use-effect (fn [] (committed!) js/undefined) #js [])
  form)

(deftest capture-observes-native-source-and-releases-it-on-unmount
  (async done
    (let [restore! (re-frame/make-restore-fn)
          schedule! local/schedule!
          connect! local/connect!
          after-page! browser-resources/after-page!
          *scheduled (atom 0)]
      (set! local/schedule! #(swap! *scheduled inc))
      (set! local/connect! #(js/Promise.resolve nil))
      ;; Timing of the shared adapter is covered in browser-resources-test.
      (set! browser-resources/after-page! (fn [start!] (start!) (fn [])))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root [local/<capture>]))
                (let [initial @*scheduled]
                  (rf/dispatch [:component-state/reset [:state :capture-test] true])
                  (await! (support/settle!))
                  (is (= (inc initial) @*scheduled))
                  (rf/dispatch [:page-return/status {:status :ready :url "/blog"}])
                  (await! (support/settle!))
                  (is (= (inc initial) @*scheduled) "Capture status does not trigger another capture")
                  (await! (support/render! root nil))
                  (rf/dispatch [:component-state/reset [:state :capture-test] false])
                  (await! (support/settle!))
                  (is (= (inc initial) @*scheduled) "Unmount releases the source subscription"))
                (finally (support/unmount! root)))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (set! local/schedule! schedule!)
                      (set! local/connect! connect!)
                      (set! browser-resources/after-page! after-page!)
                      (restore!)
                      (done)))))))

(deftest local-rendered-expanded-comments-hydrate-with-the-same-state-and-nodes
  (async done
    (-> (go-promise
          (let [restore-db! (re-frame/make-restore-fn)
                original-context @restore/*context
                original-interactive @context/*interactive?
                element (.createElement js/document "div")
                script (.createElement js/document "script")
                *root (atom nil) *errors (atom [])
                rows (mapv (fn [[id parent]] {:id id :parent-post 42 :parent-comment parent
                                              :title id :text "Locally restored content" :ts 1
                                              :reply-count (if (= id "leaf") 0 1)})
                           [["root" nil] ["child" "root"] ["grandchild" "child"]
                            ["deep" "grandchild"] ["leaf" "deep"]])
                module (dissoc user/spec :content :depends)
                form [blog/<comments-section> {:post {:id 42}}]]
            (set! (.-id element) "app")
            (.setAttribute element "data-page-build" "test-build")
            (.appendChild (.-body js/document) element)
            (try
              (rf/dispatch-sync [:init/app-db])
              (rf/dispatch-sync [:blog/expand-comment-thread [42 "root" "child" "grandchild"] true])
              (doseq [parent [nil "root" "child" "grandchild" "deep"]]
                (let [query (if parent (comments/thread-query 42 parent) (comments/root-query 42 comments/page-size))]
                  (rf/dispatch-sync [:store/scoped (scoped/query-key query)
                                     {:docs (mapv #(hash-map :id (:id %) :data %)
                                                  (filter #(= parent (:parent-comment %)) rows))}])))
              (let [db (await! (support/state-at! []))
                    saved (contract/state-for db)
                    html (render/html! db form {:modules {:user module} :restored? true :interactive? true})]
                ;; Isolated rendering must not disturb existing app state/readers.
                (is (= db (await! (support/state-at! []))))
                (set! (.-innerHTML element) html)
                (is (= 5 (.-length (.querySelectorAll element ".blog-comment-title"))))
                (let [nodes (vec (array-seq (.querySelectorAll element ".blog-comment-title")))
                      snapshot {:version 1 :build "test-build" :saved-at (.now js/Date)
                                :url (str (.-origin js/location) (.-pathname js/location) (.-search js/location))
                                :state-edn (pr-str saved) :modules [:user]}]
                  (set! (.-id script) "local-page-bootstrap")
                  (set! (.-type script) "application/json")
                  (set! (.-textContent script) (js/JSON.stringify (clj->js snapshot)))
                  (.appendChild (.-body js/document) script)
                  ;; A competing initial state must be replaced by the paired state.
                  (rf/dispatch-sync [:blog/expand-comment-thread [42 "root" "child" "grandchild"] false])
                  (local/install!)
                  (with-redefs [loader/modules {:user (reify lazy/ILoadable (ready? [_] true)
                                                            IDeref (-deref [_] module))}]
                    (await! (js/Promise.
                              (fn [resolve _]
                                (reset! *root
                                  (dom/hydrate-root element [<check> form #(resolve nil)]
                                                    {:on-recoverable-error #(swap! *errors conj (str %))}))))))
                  (is (empty? @*errors) (pr-str @*errors))
                  (is (= 5 (.-length (.querySelectorAll element ".blog-comment-title"))))
                  (is (every? true? (map identical? nodes (array-seq (.querySelectorAll element ".blog-comment-title")))))
                  (is (zero? (.-length (.querySelectorAll element ".appear-wrapper:not(.appeared),.component-spinner"))))))
              (finally
                (when @*root (support/unmount! @*root))
                (.remove element) (.remove script)
                (reset! restore/*context original-context)
                (reset! context/*interactive? original-interactive)
                (restore-db!)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest local-worker-serves-only-an-armed-pair-and-consumes-the-document
  (async done
    (-> (go-promise
          (let [url (str (.-origin js/location) "/js/blog-ssr.json")
                html "<p>Paired state document</p>"]
            (await! (.delete js/caches worker/cache-name))
            (try
              (await! (worker/save! {:url url :html html :build worker/build}))
              ;; Saving alone does not replace a normal document request.
              (let [network (await! (worker/navigation! (js/Request. url)))]
                (is (nil? (.get (.-headers network) "X-Local-Page"))))
              (await! (worker/arm! url))
              (let [cached (await! (worker/navigation! (js/Request. url)))]
                (is (= "1" (.get (.-headers cached) "X-Local-Page")))
                (is (= html (await! (.text cached)))))
              (let [cache (await! (.open js/caches worker/cache-name))]
                (is (nil? (await! (.match cache url))))
                (is (nil? (await! (.match cache worker/arm-url)))))
              (finally (await! (.delete js/caches worker/cache-name))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest local-worker-bounds-documents-and-rejects-other-builds
  (async done
    (-> (go-promise
          (await! (.delete js/caches worker/cache-name))
          (try
            (doseq [index (range (+ 3 worker/max-documents))]
              (await! (worker/save! {:url (str (.-origin js/location) "/cache-test/" index)
                                    :html "small document" :build worker/build})))
            (let [cache (await! (.open js/caches worker/cache-name))
                  entries (await! (.keys cache))]
              (is (= worker/max-documents (.-length entries))))
            (let [rejected? (await! (-> (worker/save! {:url (str (.-origin js/location) "/cache-test/bad")
                                                       :html "wrong build" :build "other-build"})
                                        (.then (fn [_] false)) (.catch (fn [_] true))))]
              (is rejected?))
            (finally (await! (.delete js/caches worker/cache-name)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest lazy-renderer-discards-saves-overtaken-while-code-loads
  (async done
    (let [restore! (re-frame/make-restore-fn)
          connect! local/connect!
          acquire! loader/acquire-code!
          message! local/message!
          *resolve (atom nil)
          pending (js/Promise. (fn [resolve _] (reset! *resolve resolve)))
          *published (atom [])
          *rendered (atom [])]
      (set! local/connect! #(js/Promise.resolve {:worker :worker}))
      (set! loader/acquire-code! (fn [id] (is (= :page-render id)) pending))
      (set! local/message! (fn [_ pair]
                            (swap! *published conj pair)
                            (js/Promise.resolve nil)))
      (let [first-save (local/save! {:version :old} false)
            second-save (local/save! {:version :current} false)]
        (@*resolve {:pair! (fn [db _]
                            (swap! *rendered conj db)
                            {:op "save" :url "current" :state db})})
        (-> (js/Promise.all #js [first-save second-save])
            (.then (fn [_]
                     (is (= [{:version :current}] @*rendered))
                     (is (= 1 (count @*published)))
                     (is (not (contains? (first @*published) :state)))))
            (.catch (fn [error] (is false (str error))))
            (.finally (fn []
                        (set! local/connect! connect!)
                        (set! loader/acquire-code! acquire!)
                        (set! local/message! message!)
                        (restore!)
                        (done))))))))
