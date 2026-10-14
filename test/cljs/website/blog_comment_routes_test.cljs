(ns website.blog-comment-routes-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [reitit.frontend :as reitit]
            [website.react :as r]
            [website.events]
            [website.subs]
            [website.modules.blog.events]
            [website.modules.blog.comments :as comments]
            [website.navigation.routes :as routes]
            [website.loader :as loader]
            [website.supabase.scoped :as scoped]
            [website.test-support :as support]))

(defn- match [path parameters]
  (assoc (reitit/match-by-path routes/router path)
         :parameters {:path parameters, :query {}}))

(def target
  {:id "113"
   :parent-post 24
   :parent-comment "child"
   :path [24 "root" "child"]
   :title "Deep comment"
   :text "Selected body"
   :ts 1})

(defn- install-comment! [row post-id]
  (r/dispatch-sync [:store/scoped
                    (scoped/query-key (comments/comment-query (:id row) post-id))
                    {:docs [{:id (:id row), :data row}]}]))

(deftest focused-navigation-reopens-ancestors-and-rejects-stale-intents
  (async done
    (let [restore! (r/make-restore-fn)]
      ;; Persistence acquisition remains at its normal effect boundary. This
      ;; test exercises accepted events and subscriptions without writing disk.
      (r/reg-fx :dispatch (fn [_]))
      (r/reg-fx :dispatch-n (fn [_]))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (r/dispatch-sync [:component-data/install [:common/route]
                              (match "/blog/post/24/comment/113"
                                     {:permalink "24", :comment-id "113"})])
            (let [route (match "/blog/post/24/comment/113"
                               {:permalink "24", :comment-id "113"})]
              (is (= :blog-post (get-in route [:data :page-route])))
              (is (some #(= [:permalink] (get-in % [:parameters :path]))
                        (get-in route [:data :controllers]))
                  "The native host controller still selects the post for the existing page view"))
            (install-comment! target 24)
            (r/dispatch-sync [:blog/expand-comment-thread [24 "root"] false])
            (r/dispatch-sync [:blog/expand-comment-thread [24 "root" "child"] false])
            (r/dispatch-sync [:blog/navigate-comment "113"])
            (let [state (await! (support/state-at! [:state :blog]))]
              (is (true? (get (:comment-thread-expanded state) [24 "root"])))
              (is (true? (get (:comment-thread-expanded state) [24 "root" "child"])))
              (is (contains? (:restore-edits state) [:comment-thread-expanded [24 "root"]])
                  "Late disk restoration must not refold the route's ancestors"))
            (r/dispatch-sync [:blog/expand-comment-thread [24 "root"] false])
            (r/dispatch-sync [:component-data/install [:common/route]
                              (match "/blog/post/24/comment/114"
                                     {:permalink "24", :comment-id "114"})])
            (r/dispatch-sync [:blog/navigate-comment "113"])
            (is (false? (get (:comment-thread-expanded
                              (await! (support/state-at! [:state :blog]))) [24 "root"]))
                "A completion for an obsolete comment cannot reopen its thread"))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))

(deftest standalone-navigation-uses-its-own-root-without-expanding-hidden-ancestors
  (async done
    (let [restore! (r/make-restore-fn)]
      (r/reg-fx :dispatch (fn [_]))
      (r/reg-fx :dispatch-n (fn [_]))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (r/dispatch-sync [:component-data/install [:common/route]
                              (match "/blog/comment/113" {:comment-id "113"})])
            (install-comment! target nil)
            (r/dispatch-sync [:blog/navigate-comment "113"])
            (let [expansion (:comment-thread-expanded
                             (await! (support/state-at! [:state :blog])))]
              (is (true? (get expansion [24 "113"])))
              (is (not (contains? expansion [24 "root"])))
              (is (not (contains? expansion [24 "root" "child"]))))
            ;; A query cached for a different post must not satisfy a post link.
            (r/dispatch-sync [:component-data/install [:common/route]
                              (match "/blog/post/25/comment/113"
                                     {:permalink "25", :comment-id "113"})])
            (r/dispatch-sync [:blog/navigate-comment "113"])
            (is (not (contains? (:comment-thread-expanded
                                  (await! (support/state-at! [:state :blog]))) [24 "root"]))))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))

(deftest history-comment-intent-retains-manual-folds
  (async done
    (let [restore! (r/make-restore-fn)
          path "/blog/post/24/comment/113"]
      (r/reg-fx :dispatch (fn [_]))
      (r/reg-fx :dispatch-n (fn [_]))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (r/dispatch-sync [:component-data/install [:common/route]
                              (match path {:permalink "24", :comment-id "113"})])
            (install-comment! target 24)
            (r/dispatch-sync [:blog/expand-comment-thread [24 "root"] false])
            (r/dispatch-sync [:component-data/install [:state :scroll :component-focus] {:path path, :scroll? false}])
            (r/dispatch-sync [:blog/navigate-comment "113"])
            (is (false? (get (:comment-thread-expanded (await! (support/state-at! [:state :blog]))) [24 "root"])))
            (r/dispatch-sync [:component-data/install [:state :scroll :component-focus] {:path path, :scroll? true}])
            (r/dispatch-sync [:blog/navigate-comment "113"])
            (is (true? (get (:comment-thread-expanded (await! (support/state-at! [:state :blog]))) [24 "root"]))))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))

(deftest focused-root-already-in-the-paged-records-is-rendered-once
  (async done
    (let [restore! (r/make-restore-fn)
          subscribe (r/subscribe-function)
          root (assoc target :id "root" :parent-comment nil :path [24] :reply-count 0)
          value (r/atom {:docs [{:id "root", :data root}]})
          focus (r/atom {:ready? true
                         :comment-id "113"
                         :row target
                         :root root
                         :parent {:id 24}})]
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (r/with-framework-bindings
              [r/subscribe
               (fn
                 ([query]
                  (case (first query)
                    :store/on-snapshot value
                    :comments/focus-state focus
                    (subscribe query)))
                 ([query options] (subscribe query options)))]
              (let [page (await! (support/subscription-value! [:comments/root-page 24]))]
                (is (= #{:root} (set (keys (:records page)))))
                (is (= ["root"] (mapv :id (vals (:records page))))
                    "Focused roots use the store's canonical record keys"))))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))

(deftest component-intent-acquires-code-without-preparing-a-parent-supplied-view
  (async done
    (let [restore! (r/make-restore-fn)
          *options (atom [])]
      (r/reg-fx :dispatch (fn [_]))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (r/dispatch-sync [:component-data/install [:common/route]
                              (match "/blog/post/24/comment/113"
                                     {:permalink "24", :comment-id "113"})])
            (r/with-framework-bindings
              [loader/load-code!
               (fn [options]
                 (swap! *options conj options)
                 (js/Promise.resolve nil))]
              (r/dispatch-sync [:navigation/activate-component])
              (await! (support/settle!))
              (is (= [{:module :blog}] @*options)
                  "The comments view cannot be prepared without its required post argument")))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))
