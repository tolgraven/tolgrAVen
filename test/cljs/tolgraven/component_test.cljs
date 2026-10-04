(ns tolgraven.component-test
  (:require [cljs.test :refer-macros [deftest is]]
            [react-dom :as react-dom]
            [react :as react]
            [reagent.core :as r]
            [reagent.dom.client :as dom]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.ui :as ui]
            [tolgraven.component :as component]
            [tolgraven.component-fixture]
            [tolgraven.util :as util]))

(defn- flush! [] (react-dom/flushSync #(r/flush)))
(defn- render! [root form] (react-dom/flushSync #(.render root (r/as-element form))))

(defn- with-root [test!]
  (let [element (.createElement js/document "div")
        root (dom/create-root element)]
    (.appendChild (.-body js/document) element)
    (try (test! root element)
         (finally (react-dom/flushSync #(dom/unmount root)) (.remove element)))))

(defc <counter> "Per-instance bindings, metadata and current destructured args."
  {:example true :features [:props]}
  [spec {:keys [label]} & children]
  :let [*count (r/atom 0)]
  (into [:button.counter {:class "base" :style {:color "red"}
                         :on-click #(swap! *count inc)}
         (str label ":" @*count)] children))

(defc <form-two> [label]
  (let [*count (r/atom 0)]
    (fn [label]
      [:button {:on-click #(swap! *count inc)} (str label ":" @*count)])))

(defc <destructured> [{:keys [title]}]
  [:p title])

(defc <empty-args> [] [:span "No arguments"])
(defc <fragment> [spec] [:<> [:span "Fragment"]])
(defc <component-root> [spec] [<empty-args>])
(defc <empty-element> [spec] [:div])
(defn <native-child> [_] (react/createElement "b" nil "Native child"))
(defc <native-root> [spec] [:> <native-child>])
(defc <raw-root> [spec] [:r> <native-child> #js {}])
(defc <functional-root> [spec] [:f> (fn [] [:b "Functional child"])])

(defc <own-error> {:features [:error-boundary :lifecycle]} [spec *broken?]
  :let [_ (when (:break-init? spec) (throw (js/Error. "Initialization failed")))]
  (when @*broken? (throw (js/Error. "Own render failed")))
  [:p "Recovered own render"])

(defn <throws> [*broken?]
  (when @*broken? (throw (js/Error. "Descendant failed")))
  [:p "Recovered descendant"])
(defc <child-error> {:features [:error-boundary]} [spec *broken?] [:div [<throws> *broken?]])
(defc <nested> {:features [:error-boundary]} [spec *broken?] [:article [:p "Healthy parent"] [<own-error> {} *broken?]])

(deftest exported-defc-vars-and-page-navigation-recovery
  (with-redefs [util/log (fn [& _])]
    (with-root
      (fn [root element]
        (render! root [ui/safe :page [<throws> (r/atom true)] "/blog"])
        (is (some? (.querySelector element "[role=alert]")))
        ;; Changing children on the same route must not retry on every render.
        (render! root [ui/safe :page [(component/resolve-view #'<empty-args>)] "/blog"])
        (is (some? (.querySelector element "[role=alert]")))
        ;; A new route owns a new boundary, even if the exported page is a defc Var.
        (render! root [ui/safe :page [(component/resolve-view #'<empty-args>)] "/services"])
        (is (nil? (.querySelector element "[role=alert]")))
        (is (= "No arguments" (.-textContent element)))
        (render! root [ui/safe :page [(component/resolve-view #'<destructured>) {:title "Post route"}] "/blog/post/2"])
        (is (= "Post route" (.-textContent element)))))))

(deftest local-state-props-fragments-and-argument-forwarding
  (with-root
    (fn [root element]
      (render! root [<counter> {:props {:class "caller" :style {:background-color "blue"}}
                               :classes ["extra"]} {:label "First"} [:i "Child"]])
      (let [button (.querySelector element "button")]
        (doseq [class ["counter" "base" "caller" "extra"]]
          (is (.contains (.-classList button) class)))
        (is (= "red" (.. button -style -color)))
        (is (= "blue" (.. button -style -backgroundColor)))
        (.click button) (flush!))
      (render! root [<counter> {} {:label "Updated"} [:i "New child"]])
      (is (= "Updated:1New child" (.-textContent element)))
      (render! root [<form-two> "First"])
      (.click (.querySelector element "button")) (flush!)
      (render! root [<form-two> "Second"])
      (is (= "Second:1" (.-textContent element)))
      (render! root [<destructured> {:title "Domain data" :props {:id "must-not-leak"}}])
      (is (= "Domain data" (.-textContent element)))
      (is (nil? (.querySelector element "#must-not-leak")))
      (render! root [<fragment> {:props {:id "must-not-leak"}}])
      (is (= "SPAN" (.-tagName (.-firstElementChild element))))
      (render! root [<component-root> {:props {:id "must-not-leak"}}])
      (is (= "No arguments" (.-textContent element)))
      (doseq [[component expected] [[<native-root> "Native child"]
                                    [<raw-root> "Native child"]
                                    [<functional-root> "Functional child"]]]
        (render! root [component {:props {:id "must-not-leak"}}])
        (is (= expected (.-textContent element))))
      (render! root [<empty-element> {}])
      (is (= 0 (.-length (.-childNodes (.-firstElementChild element))))))))

(deftest boundaries-isolate-errors-and-retry-with-current-args
  (let [*messages (atom [])]
    (with-redefs [util/log (fn [& message] (swap! *messages conj message))]
      (doseq [component [<own-error> <child-error> <nested>]]
        (with-root
          (fn [root element]
            (let [*broken? (r/atom true)]
              (render! root [:div [:aside "Healthy sibling"] [component {} *broken?]])
              (is (= 1 (.-length (.querySelectorAll element "[role=alert]"))))
              (is (.includes (.-textContent element) "Healthy sibling"))
              (when (= component <nested>)
                (is (.includes (.-textContent element) "Healthy parent") (.-textContent element)))
              (reset! *broken? false) (flush!)
              (is (some? (.querySelector element "[role=alert]")) "Failure stays until explicit retry")
              (.click (.querySelector element "[role=alert] button")) (flush!)
              (is (nil? (.querySelector element "[role=alert]")))
              (is (.includes (.-textContent element) "Recovered"))))))
      (with-root
        (fn [root element]
          (let [*broken? (r/atom false)]
            (render! root [<own-error> {:break-init? true} *broken?])
            (is (.includes (.-textContent element) "Initialization failed"))
            (render! root [<own-error> {} *broken?])
            (.click (.querySelector element "button")) (flush!)
            (is (= "Recovered own render" (.-textContent element))))))
      (with-root
        (fn [root element]
          (let [*broken? (r/atom true)]
            (render! root [ui/safe :example [<throws> *broken?]])
            (is (some? (.querySelector element "[role=alert]")))
            (reset! *broken? false)
            (.click (.querySelector element "button")) (flush!)
            (is (= "Recovered descendant" (.-textContent element))))))
      (is (= 5 (count @*messages)))
      (is (every? #(= :error (first %)) @*messages)))))

(deftest lifecycle-uses-current-spec-and-preserves-both-refs
  (let [*events (atom []) *base-ref (atom nil) *caller-ref (atom nil)
        base-ref! #(reset! *base-ref %) caller-ref! #(reset! *caller-ref %)
        component (tolgraven.component/create-component
                   "test" "refs" {:features [:props :lifecycle]}
                   (fn [_] (fn [_] [:div {:ref base-ref!} "Refs"])))]
    (with-root
      (fn [root element]
        (render! root [component {:props {:ref caller-ref!}
                                 :init #(swap! *events conj :init)
                                 :exit #(swap! *events conj :stale-exit)}])
        (is (= (.-firstElementChild element) @*base-ref @*caller-ref))
        (render! root [component {:props {:ref caller-ref!}
                                 :exit #(swap! *events conj :latest-exit)}])))
    (is (nil? @*base-ref))
    (is (nil? @*caller-ref))
    (is (= [:init :latest-exit] @*events))))

(deftest errors-after-mount-and-lifecycle-errors-remain-local
  (with-redefs [util/log (fn [& _])]
    (with-root
      (fn [root element]
        (let [*broken? (r/atom false)]
          (render! root [:div [<counter> {} {:label "Unaffected"}]
                         [<own-error> {} *broken?]])
          (.click (.querySelector element "button")) (flush!)
          (reset! *broken? true) (flush!)
          (is (some? (.querySelector element "[role=alert]")))
          (is (.includes (.-textContent element) "Unaffected:1"))
          (reset! *broken? false)
          (.click (.querySelector element "[role=alert] button")) (flush!)
          (is (.includes (.-textContent element) "Unaffected:1"))
          (is (nil? (.querySelector element "[role=alert]"))))))
    (with-root
      (fn [root element]
        (let [*broken? (r/atom false)]
          (render! root [<own-error> {:init (fn [_] (throw (js/Error. "Mount failed")))} *broken?])
          (is (.includes (.-textContent element) "Mount failed"))
          (render! root [<own-error> {} *broken?])
          (.click (.querySelector element "button")) (flush!)
          (is (= "Recovered own render" (.-textContent element))))))))

(deftest features-compose-in-order-and-disabled-features-do-not-mount
  (let [*events (atom [])]
    (component/register-feature! :test-transform
                                 {:transform (fn [form _ config] [:section {:data-feature config} form])})
    (component/register-feature! :test-wrapper
                                 {:wrap (fn [form _ config] [:article {:data-feature config} form])})
    (component/register-feature! :test-disabled
                                 {:setup (fn [_] (swap! *events conj :setup))})
    (with-root
      (fn [root element]
        (let [view (component/create-component "fixture" "composition"
                                                {:features [[:test-wrapper "outer"]
                                                            [:test-transform "inner"]
                                                            [:test-disabled false]]}
                                                (fn [] (fn [] [:p "Composed body"]))) ]
          (render! root [view])
          (is (= "Composed body" (.-textContent element)))
          (is (some? (.querySelector element "article[data-feature=outer] > section[data-feature=inner] > p")))
          (is (empty? @*events)))))))
