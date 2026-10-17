(ns website.supabase-runtime-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as string]
            [shadow.lazy :as lazy]
            [website.react :as r]
            [website.component.restore :as restore]
            [website.events]
            [website.loader.code :as code]
            [website.render-context :as context]
            [website.ssr.render :as ssr]
            [website.subs]
            [website.modules.blog.data :as blog-data]
            [website.supabase.query :as query]
            [website.supabase.runtime :as runtime]
            [website.test-support :as support]))

(defn pending-reader! [opts]
  (r/with-framework-bindings [runtime/ensure-code! #(js/Promise. (fn [_ _]))
                runtime/ensure! #(js/Promise.resolve nil)]
    (runtime/ensure-query! opts)))

(deftest mounted-reader-recovers-after-a-later-chunk-acquisition
  (let [original @runtime/*api
        *acquired (atom 0)
        *disposed (atom 0)]
    (try
      (reset! runtime/*api nil)
      (let [reader (pending-reader! {:path-collection [:blog-posts]})]
        (reset! runtime/*api
                {:ensure-query! (fn [_]
                                  (swap! *acquired inc)
                                  (r/make-reaction #(hash-map :docs [])
                                    :on-dispose #(swap! *disposed inc)))})
        (is (= 1 @*acquired) "The original pending binding acquires the retried module")
        (is (= {:docs []} @reader))
        (r/dispose! reader)
        (is (= 1 @*disposed) "Its pooled reader releases with its owner"))
      (finally (reset! runtime/*api original)))))

(deftest disposed-pending-reader-does-not-acquire-late-code
  (let [original @runtime/*api
        *acquired (atom 0)
        *disposed (atom 0)]
    (try
      (reset! runtime/*api nil)
      (let [reader (pending-reader! {:path-collection [:blog-posts]})]
        (r/dispose! reader)
        (reset! runtime/*api {:ensure-query! (fn [_] (swap! *acquired inc))})
        (is (zero? @*acquired) "Unmounting removes the code installation observer"))
      (reset! runtime/*api nil)
      (let [reader (pending-reader! {:path-collection [:blog-posts]})]
        (reset! runtime/*api
                {:ensure-query! (fn [_]
                                  (r/make-reaction (constantly nil)
                                    :on-dispose #(swap! *disposed inc)))})
        (r/dispose! reader)
        (is (= 1 @*disposed) "Disposal before the next render releases a newly acquired reader"))
      (finally (reset! runtime/*api original)))))

(deftest first-cached-read-does-not-depend-on-a-warmed-core-subscription
  (let [restore! (r/make-restore-fn)
        original @runtime/*api
        opts (blog-data/page-query 0 blog-data/page-size)
        key (pr-str (query/normalize-query opts))
        expected {:docs [{:id "42"
                          :data {:id 42
                                 :title "Cached SSR article"
                                 :text "Visible before SDK readiness"}}]}
        *reader (atom nil)]
    (try
      (reset! runtime/*api nil)
      (r/dispatch-sync [:set [:store :scoped key] expected])
      (r/clear-subscription-cache!)
      (reset! *reader (pending-reader! opts))
      ;; Alpha normalizes its query to a map. Reading the ordinary subscription
      ;; first would hide a broken alpha handler through their shared cache.
      (is (= expected @@*reader) "Restored content is visible on the first read before SDK code")
      (is (= expected (get-in @(r/subscribe [:get :store]) [:scoped key])))
      (finally
        (when @*reader (r/dispose! @*reader))
        (reset! runtime/*api original)
        (restore!)))))

(r/defc <pending-store-value> [id reader gate *released? *attempts]
  (when (and gate (not context/*server?*) (not @*released?))
    (swap! *attempts update :pending (fnil conj #{}) id)
    (throw gate))
  [:p {:data-store-owner id} (get-in @reader [:docs 0 :data :text])])

(r/defc <pending-store-owner> [id gate *released? *attempts *commits]
  (let [reader (r/subscribe [:store/on-snapshot (blog-data/post-query id)])]
    (swap! *attempts update-in [:owners id] (fnil inc 0))
    (r/use-layout-effect (fn [] (swap! *commits conj id) nil) [])
    ;; Finish this render so Reagent owns any captured factory derefs. A
    ;; descendant's independent dependency then prevents this owner's commit.
    [<pending-store-value> id reader gate *released? *attempts]))

(r/defc <pending-store-shell>
  [gate *released? *show-second? *attempts *commits *shell-committed? acquire!]
  (r/use-layout-effect (fn [] (reset! *shell-committed? true) nil) [])
  (let [pending (r/use-memo
                  #(r/as-element
                     [r/suspense {:fallback (r/as-element [:p {:data-store-fallback true} "Waiting"])}
                      [<pending-store-owner> 901 gate *released? *attempts *commits]])
                  [gate *released? *attempts *commits])]
    [:div
     [:button {:on-click (fn [] (acquire!) (reset! *show-second? true))} "Finish database code"]
     pending
     (when @*show-second?
       [<pending-store-owner> 902 nil *released? *attempts *commits])]))

(defn- observe-pending-store! [{:keys [element form *root *attempts *commits
                                     *shell-committed? *warnings *recoveries *acquired]}]
  (go-promise
    (r/dispatch-sync [:init/app-db])
    (doseq [id [901 902]]
      (let [opts (blog-data/post-query id)]
        (r/dispatch-sync
          [:set [:store :scoped (pr-str (query/normalize-query opts))]
           {:docs [{:id (str id), :data {:id id, :text (str "Stored " id)}}]}])))
    (let [db (await! (support/state-at! []))]
      (set! (.-innerHTML element) (ssr/html! db form {:modules {}})))
    (reset! *attempts {})
    (reset! *commits [])
    (restore/begin! {:hydrate? true})
    (let [server-node (.querySelector element "[data-store-owner='901']")]
      (is (= "Stored 901" (.-textContent server-node)))
      (reset! *root (r/hydrate-root element (restore/document-form form)
                     {:on-recoverable-error #(swap! *recoveries conj (str %))}))
      (await! (support/wait-for! #(and @*shell-committed? (contains? (:pending @*attempts) 901))
                                "The actual store subscription owner did not begin pending hydration"))
      (is (not (some #{901} @*commits)) "The query owner has not committed")
      (is (empty? @*acquired) "The SDK reader is not available before code finishes")
      (.click (.querySelector element "button"))
      (await! (support/wait-for! #(some #{902} @*commits) "The independent ready query did not commit"))
      (await! (support/settle!))
      (await! (support/settle!))
      (is (= #{901 902} (set @*acquired)) "Real runtime acquisition installs both SDK-owned readers")
      (is (empty? @*warnings) (str "Unexpected precommit/hydration warnings: " (pr-str @*warnings)))
      (is (empty? @*recoveries))
      (is (identical? server-node (.querySelector element "[data-store-owner='901']")))
      (is (nil? (.querySelector element "[data-store-fallback]"))
          "SDK arrival must not replace completed SSR while an unrelated dependency is pending")
      server-node)))

(defn- finish-pending-store! [{:keys [element *released? release! *commits *warnings *recoveries]}
                            server-node]
  (go-promise
    (reset! *released? true)
    (release! nil)
    (await! (support/wait-for! #(some #{901} @*commits) "The original query owner did not finish"))
    (is (identical? server-node (.querySelector element "[data-store-owner='901']")))
    (is (= "Stored 901" (.-textContent (.querySelector element "[data-store-owner='901']"))))
    (is (= "Stored 902" (.-textContent (.querySelector element "[data-store-owner='902']"))))
    (is (empty? @*warnings))
    (is (empty? @*recoveries))))

(deftest store-acquisition-does-not-track-an-uncommitted-hydration-owner
  (async done
    (let [restore-db! (r/make-restore-fn)
          before {:api @runtime/*api
                  :loading @runtime/*loading
                  :client @runtime/*client
                  :modules code/modules
                  :load lazy/load
                  :context @restore/*context
                  :error (.-error js/console)
                  :warn (.-warn js/console)}
          element (.createElement js/document "div")
          *root (atom nil)
          *resolve-code (atom nil)
          code-ready (js/Promise. (fn [resolve _] (reset! *resolve-code resolve)))
          *resolve-gate (atom nil)
          gate (js/Promise. (fn [resolve _] (reset! *resolve-gate resolve)))
          *released? (atom false)
          *show-second? (r/atom false)
          *attempts (atom {})
          *commits (atom [])
          *shell-committed? (atom false)
          *warnings (atom [])
          *recoveries (atom [])
          *acquired (atom [])
          *disposed (atom [])
          api {:ensure-query! (fn [opts]
                                (let [id (-> opts :where first last)]
                                  (swap! *acquired conj id)
                                  (r/make-reaction
                                    #(hash-map :docs [{:id (str id), :data {:id id, :text (str "Stored " id)}}])
                                    :on-dispose #(swap! *disposed conj id))))}
          spec {:client api}
          module (reify lazy/ILoadable
                   (ready? [_] false)
                   IDeref (-deref [_] spec))
          acquire! #(@*resolve-code spec)
          fixture {:element element
                   :*root *root
                   :*released? *released?
                   :release! #(@*resolve-gate %)
                   :*attempts *attempts
                   :*commits *commits
                   :*shell-committed? *shell-committed?
                   :*warnings *warnings
                   :*recoveries *recoveries
                   :*acquired *acquired
                   :form [<pending-store-shell> gate *released? *show-second? *attempts
                          *commits *shell-committed? acquire!]}
          capture! (fn [original args]
                     (let [message (string/join " " args)]
                       (when (or (string/includes? message "hasn't mounted yet")
                                 (string/includes? message "hydrated")
                                 (string/includes? message "Hydration failed"))
                         (swap! *warnings conj message)))
                     (.apply original js/console (to-array args)))]
      (-> (js/Promise.resolve nil)
          (.then (fn []
                   (.appendChild (.-body js/document) element)
                   (reset! runtime/*api nil)
                   (reset! runtime/*loading nil)
                   ;; This is the SDK boundary. No Auth, HTTP or provider init is
                   ;; involved; ensure-code!/ensure-query! themselves remain real.
                   (reset! runtime/*client #js {})
                   (set! code/modules (assoc code/modules :supabase module))
                   (set! lazy/load
                         (fn
                           ([value]
                            (if (identical? module value) code-ready ((:load before) value)))
                           ([value callback]
                            (if (identical? module value) (.then code-ready callback)
                              ((:load before) value callback)))))
                   (set! (.-error js/console) (fn [& args] (capture! (:error before) args)))
                   (set! (.-warn js/console) (fn [& args] (capture! (:warn before) args)))
                   (observe-pending-store! fixture)))
          (.then #(finish-pending-store! fixture %))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (r/unmount @*root))
                      (reset! *released? true)
                      (@*resolve-gate nil)
                      (acquire!)
                      ;; Drain released module acquisition before restoring its
                      ;; transport and shared API, including an early test failure.
                      (-> code-ready
                          (.then (fn [] (support/settle!)))
                          (.finally (fn []
                                      (r/clear-subscription-cache!)
                                      (is (= (frequencies @*acquired) (frequencies @*disposed))
                                          "Every installed SDK reader is disposed")
                                      (set! code/modules (:modules before))
                                      (set! lazy/load (:load before))
                                      (reset! runtime/*api (:api before))
                                      (reset! runtime/*loading (:loading before))
                                      (reset! runtime/*client (:client before))
                                      (reset! restore/*context (:context before))
                                      (set! (.-error js/console) (:error before))
                                      (set! (.-warn js/console) (:warn before))
                                      (.remove element)
                                      (restore-db!)
                                      (done))))))))))
