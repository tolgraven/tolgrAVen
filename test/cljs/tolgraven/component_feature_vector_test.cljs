(ns tolgraven.component-feature-vector-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [tolgraven.react :as r]


            [tolgraven.component :as component]
            [tolgraven.component.registry :as registry]
            [tolgraven.schema.declarations :as schemas]
            [tolgraven.validation.runtime :as validation]
            [tolgraven.test-support :as support]
            [tolgraven.macros :refer-macros [defc]]))

(component/register-feature!
  :vector-label
  {:transform (fn [form _ config]
                (update form 1 assoc :data-title (:title config)
                                    :data-feature-count (:feature-count config)))})

(defc <article>
  {:features [:props [:vector-label (fn [spec title & _]
                                     {:title title
                                      :feature-count (count (:features spec))})]]
   :spec-schema schemas/component-spec}
  [spec :- schemas/component-spec title :- :string & children :- :string]
  :let [initial-features (:features spec)
        *count (r/atom 0)]
  [:article {:data-spec (pr-str spec), :data-initial (pr-str initial-features)}
   [:button {:on-click #(swap! *count inc)} (str @*count)]
   [:h2 title]
   (into [:p] children)])

(defc <domain> [data] [:pre (pr-str data)])
(defc <explicit-domain> {:spec false, :features [:props]} [spec] [:pre (pr-str spec)])

(deftest feature-vector-boundary-normalizes-only-spec-capable-arguments
  (let [features [[:appear false] :seen]
        args [features "Title" "Child"]]
    (is (= [{:features features} "Title" "Child"]
           (registry/normalize-args (component/component-spec <article>) args)))
    (is (= [{:features []}] (registry/normalize-args {:options {:spec true}} [[]])))
    (doseq [view [<domain> <explicit-domain>]]
      (is (identical? args (registry/normalize-args (component/component-spec view) args))))
    (doseq [args' [[{:props {:id "kept"}} "Title"] [nil "Title"] []]]
      (is (identical? args' (registry/normalize-args (component/component-spec <article>) args'))))))

(deftest shorthand-validates-the-canonical-spec-and-positional-contracts
  (let [enabled? @validation/*enabled?
        definition (component/component-spec <article>)]
    (try
      (reset! validation/*enabled? true)
      (is (= [{:features [:vector-label]} "Title" "Child"]
             (registry/validate-args! definition [[:vector-label] "Title" "Child"])))
      (is (thrown? js/Error (registry/validate-args! definition [[[:vector-label]] "Title"])))
      (is (thrown? js/Error (registry/validate-args! definition [[] 42])))
      (is (thrown? js/Error (registry/validate-args! definition [[] "Title" 42])))
      (reset! validation/*enabled? false)
      (is (= [{:features []} "Title"] (registry/validate-args! definition [[] "Title"])))
      (finally (reset! validation/*enabled? enabled?)))))

(deftest dependency-functions-receive-the-same-spec-as-rendering
  (let [definition (component/definition
                     "fixture" "vector-dependencies"
                     {:spec true
                      :depends (fn [spec title]
                                 [{:source :vector-dependency
                                   :query [title (:features spec)]}])}
                     (fn [& _] (fn [& _] [:p "Body"])))]
    (is (= [{:source :vector-dependency, :query ["Title" [:data]]}]
           (component/dependencies definition [[:data] "Title"])))
    (is (= (component/dependencies definition [[:data] "Title"])
           (component/dependencies definition [{:features [:data]} "Title"])))))

(deftest feature-vectors-and-maps-render-identical-server-markup
  (doseq [features [[] [:vector-label] [[:vector-label false]]
                    [[:vector-label {:title "Override"}]]]]
    (is (= (r/render-to-string [<article> features "Title" "One" "Two"])
           (r/render-to-string [<article> {:features features} "Title" "One" "Two"]))))
  (doseq [view [<domain> <explicit-domain>]]
    (is (= "<pre>[:unknown-feature]</pre>" (r/render-to-string [view [:unknown-feature]])))))

(defc <layout>
  {:features [:props [:vector-label (fn [spec title & _]
                                     {:title title
                                      :feature-count (count (:features spec))})]]}
  [spec title & forms]
  (into [:article {:data-spec (pr-str spec)} [:h2 title]] forms))

(deftest custom-container-templates-merge-props-into-feature-vector-specs
  (let [view (component/create-component
               "fixture" "vector-container"
               {:spec true
                :features [[:container {:form [<layout> [:vector-label] "Layout"]}]]}
               (fn [_] (fn [_] [:p "Body"])))
        html (r/render-to-string [view {:props {:id "template-props"}}])]
    (is (.includes html "id=\"template-props\""))
    (is (.includes html "data-title=\"Layout\""))
    (is (.includes html "<p>Body</p>"))))

(deftest map-vector-updates-preserve-native-identity-state-and-variadic-children
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))]
            (.appendChild (.-body js/document) element)
            (try
              (await! (support/render! root [<article> [:vector-label] "First" "One" "Two"]))
              (let [article (.querySelector element "article")]
                (is (= "{:features [:vector-label]}" (.getAttribute article "data-spec")))
                (is (= "[:vector-label]" (.getAttribute article "data-initial")))
                (is (= "First" (.getAttribute article "data-title")))
                (is (= "1" (.getAttribute article "data-feature-count")))
                (is (= "OneTwo" (.-textContent (.querySelector element "p"))))
                (.click (.querySelector element "button"))
                (await! (support/settle!))
                (await! (support/render! root [<article> {:props {:id "map-props"}
                                                        :features [[:vector-label false]]}
                                               "Second" "Three"]))
                (is (identical? article (.querySelector element "article")))
                (is (= "map-props" (.-id article)))
                (is (nil? (.getAttribute article "data-title")))
                (is (= "1" (.-textContent (.querySelector element "button"))))
                (await! (support/render! root [<article> [] "Third" "Four"]))
                (is (identical? article (.querySelector element "article")))
                (is (= "Third" (.getAttribute article "data-title")))
                (is (= "0" (.getAttribute article "data-feature-count")))
                (is (= "1" (.-textContent (.querySelector element "button"))))
                (is (= "Four" (.-textContent (.querySelector element "p")))))
              (finally (support/unmount! root) (.remove element)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest vector-spec-hydration-retains-server-nodes-and-attaches-events
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                form [<article> [:vector-label] "Hydrated" "Child"]]
            ;; Fixture DOM setup models the server document before React owns it.
            (set! (.-innerHTML element) (r/render-to-string form))
            (.appendChild (.-body js/document) element)
            (let [article (.querySelector element "article")
                  button (.querySelector element "button")
                  root (r/hydrate-root element form)]
              (try
                (await! (support/settle!))
                (is (identical? article (.querySelector element "article")))
                (is (identical? button (.querySelector element "button")))
                (.click button)
                (await! (support/wait-for! #(= "1" (.-textContent button))))
                (is (= "Hydrated" (.getAttribute article "data-title")))
                (finally (r/unmount root) (.remove element))))))
        (.catch #(is false (str %)))
        (.finally done))))
