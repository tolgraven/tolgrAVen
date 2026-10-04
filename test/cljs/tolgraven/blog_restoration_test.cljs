(ns tolgraven.blog-restoration-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [reagent.core :as r]
            [react-dom :as react-dom]
            [reagent.dom.client :as dom]
            [tolgraven.blog.cache :as cache]
            [tolgraven.blog.views :as blog]
            [tolgraven.component.storage :as storage]
            [tolgraven.component.restore :as restore]
            [tolgraven.supabase.scoped :as scoped]
            [tolgraven.user.views :as user]
            [tolgraven.user.module :as user-module]
            [tolgraven.loader :as loader]
            [shadow.lazy :as lazy]
            [tolgraven.user.events]
            [tolgraven.events]
            [tolgraven.subs]))

(deftest restore-only-public-queries-and-retain-newer-server-data
  (let [before @rfdb/app-db buckets @storage/*buckets
        query (scoped/query-key {:path-collection [:blog-comments] :scoped? true})
        path [:store :scoped query]
        fold [:state :blog :comment-thread-expanded]
        snapshots {[:state path] {:value {:docs [{:id "cached"}]}}
                   [:state fold] {:value {[28 "root"] false}}}]
    (try
      (reset! rfdb/app-db {})
      (swap! storage/*buckets assoc :public snapshots)
      (with-redefs [storage/read! (fn [id _] (get snapshots id)) storage/track! (fn [& _])]
        (cache/restore!)
        (is (= {:docs [{:id "cached"}]} (get-in @rfdb/app-db path)))
        (is (false? (get-in @rfdb/app-db (conj fold [28 "root"]))))
        (swap! rfdb/app-db assoc-in path {:docs [{:id "fresh-server"}]})
        (cache/restore!)
        (is (= "fresh-server" (get-in @rfdb/app-db (into path [:docs 0 :id])))))
      (is (false? (boolean (cache/public-query? (pr-str {:scoped? true :path-collection [:gpt]})))))
      (is (false? (boolean (cache/public-query? "malformed ["))))
      (finally (reset! rfdb/app-db before) (reset! storage/*buckets buckets)))))

(deftest returning-comments-render-immediately-and-keep-folded-threads-folded
  (let [element (.createElement js/document "div") root (dom/create-root element)
        before @restore/*context original rf/subscribe
        author {:id "author" :name "Writer"}
        comment (fn [id] {:id id :title (str "Comment " id) :user author :text "Restored body" :ts 1})
        roots (r/atom (into {} (map #(vector % (comment %))) (range 5)))
        replies (r/atom {"reply" (comment "reply")})]
    (try
      (restore/begin! {:back? true})
      (with-redefs [loader/modules {:user (reify lazy/ILoadable
                                           (ready? [_] true)
                                           IDeref (-deref [_] (dissoc user-module/spec :content :depends)))}
                    rf/subscribe
                    (fn
                      ([q]
                       (case (first q)
                        :comments/for-q-flat (if (= 2 (count q)) roots replies)
                        :comments/thread-expanded? (r/atom false)
                        :blog/state (r/atom false)
                        :user/active-user (r/atom nil)
                        :comments/adding? (r/atom false)
                        (original q)))
                      ([q _] (original q)))]
        (react-dom/flushSync #(dom/render root [blog/<comments-section> {:post {:id 28}}]))
        (is (= 4 (.-length (.querySelectorAll element ".blog-comment-title")))
            "The normal collapsed root limit applies in the first commit")
        (is (.contains (.-classList (.querySelector element ".blog-comments")) "appeared"))
        (is (zero? (.-length (.querySelectorAll element ".appear-wrapper:not(.appeared)")))
            "Restored comments have no hidden entrance frame")
        (is (= 4 (.-length (.querySelectorAll element ".blog-comment-collapsed-placeholder"))))
        (is (not (.includes (.-textContent element) "Comment reply"))))
      (finally (react-dom/flushSync #(dom/unmount root)) (reset! restore/*context before)))))

(deftest user-panel-closes-state-immediately-and-component-owns-exit
  (async done
    (let [element (.createElement js/document "div") root (dom/create-root element)
          before @rfdb/app-db context @restore/*context]
      (.appendChild (.-body js/document) element)
      (restore/begin! {})
      (rf/dispatch-sync [:user/close-ui])
      (react-dom/flushSync #(dom/render root [user/<user-section>]))
      (is (nil? (.querySelector element ".user-section-wrapper")))
      (rf/dispatch-sync [:user/open-ui :login])
      (react-dom/flushSync #(r/flush))
      (is (some? (.querySelector element ".user-section-wrapper")))
      (rf/dispatch-sync [:user/close-ui])
      (react-dom/flushSync #(r/flush))
      (is (= [:closed] (get-in @rfdb/app-db [:state :user-section])))
      (is (some? (.querySelector element ".user-section-wrapper.exiting")))
      ;; Reopening before exit completion must retain the same mounted wrapper.
      (let [panel (.querySelector element ".user-section-wrapper")]
        (rf/dispatch-sync [:user/open-ui :login])
        (react-dom/flushSync #(r/flush))
        (is (identical? panel (.querySelector element ".user-section-wrapper"))))
      (rf/dispatch-sync [:user/close-ui])
      (react-dom/flushSync #(r/flush))
      (js/setTimeout
       (fn []
         (react-dom/flushSync #(r/flush))
         (is (nil? (.querySelector element ".user-section-wrapper")) "Exited DOM is removed")
         (react-dom/flushSync #(dom/unmount root))
         (.remove element)
         (reset! rfdb/app-db before)
         (reset! restore/*context context)
         (done)) 250))))

(deftest avatar-does-not-overlay-a-placeholder-while-loading
  (let [element (.createElement js/document "div")
        root (dom/create-root element)
        *profile (r/atom nil)
        before @rfdb/app-db
        fallback "/img/avatar-fallback-test.png"
        avatar "https://example.invalid/author-avatar.png"
        render! #(react-dom/flushSync (fn [] (r/flush)))]
    (try
      (swap! rfdb/app-db assoc-in [:content :common :user-avatar-fallback] fallback)
      (react-dom/flushSync #(dom/render root [(fn [] [user/user-avatar @*profile "blog-user-avatar"])]))
      (let [image (.querySelector element "img")]
        (is (= "hidden" (.. image -style -visibility)))
        (is (nil? (.getAttribute image "src")) "Pending profiles do not request the default logo"))
      (reset! *profile {:name "Author" :avatar avatar})
      (render!)
      (is (= 1 (.-length (.querySelectorAll element "img"))) "No fallback layer before image load")
      (is (= avatar (.getAttribute (.querySelector element "img") "src")))
      (.dispatchEvent (.querySelector element "img") (js/Event. "error"))
      (render!)
      (is (= fallback (.getAttribute (.querySelector element "img") "src")) "Real failures retain a fallback")
      (reset! *profile {:name "Other author" :avatar "https://example.invalid/other-avatar.png"})
      (render!)
      (is (= "https://example.invalid/other-avatar.png" (.getAttribute (.querySelector element "img") "src"))
          "An earlier failure does not poison a different avatar")
      (reset! *profile {:name "No avatar"})
      (render!)
      (is (= fallback (.getAttribute (.querySelector element "img") "src")))
      (finally
        (react-dom/flushSync #(dom/unmount root))
        (reset! rfdb/app-db before)))))
