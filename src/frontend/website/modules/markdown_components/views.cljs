(ns website.modules.markdown-components.views
  (:require [website.component.registry :as registry]
            [website.loader :as loader]
            [website.component.data :as data]
            [website.components.content.layout :as layout]
            [website.macros :refer-macros [defc]]
            [website.validation :as validation]
            [website.render-context :as context]
            [website.react :as r]
            [website.component.public-catalog :as catalog]
            [website.markdown.authoring :as model]
            [website.modules.markdown-components.widgets]))

(defn binding-dependencies [{:keys [arguments]}]
  (mapv (fn [path] {:source :app-db, :path path})
        (distinct (keep :path (vals arguments)))))

(defn values [{:keys [arguments]}]
  (into {} (map (fn [[key {:keys [path literal]}]]
                  [key (if path (:value (data/snapshot {:source :app-db, :path path})) literal)])) arguments))

(defc <unavailable> [message]
  [:aside.content-component-error {:role "status"} message])

(def depth-context (r/create-context 0))

(defc <render>
  "Resolve a selected public component through its normal module and dependencies."
  {:features [:error-boundary]
   :args-schema [:cat [:or model/invocation-schema [:map [:error :string]]]]
   :depends (fn [invocation] (binding-dependencies invocation))}
  [{:keys [name error] :as invocation}]
  (let [entry (get catalog/by-name name)
        props (values invocation)
        geometry (layout/geometry props)
        depth (r/use-context depth-context)
        _' (when-not (validation/ready?) @(r/subscribe [:loader/module :coercion]))
        {:keys [component definition]} (registry/public-component name)
        options (:options definition)
        positional? (contains? props :args)
        args (if positional? (:args props) [(apply dissoc props layout/attribute-fields)])
        invalid? (or (and (validation/ready?) (validation/explain layout/schema geometry))
                     (not (vector? args))
                     (and (:args-schema options) (validation/ready?) (validation/explain (:args-schema options) args))
                     (and (:spec-schema options) (validation/ready?) (validation/explain (:spec-schema options) (first args))))]
    (cond
      error [<unavailable> error]
      (nil? entry) [<unavailable> "Component unavailable on this site"]
      (>= depth 8) [<unavailable> "Component nesting limit reached"]
      (and (nil? component) (or (= :main (:owner entry)) (= :markdown-components (:owner entry))))
      [<unavailable> "Preparing component…"]
      (not (validation/ready?)) [<unavailable> "Preparing component…"]
      invalid? [<unavailable> (str "Check the arguments for " name)]
      :else [:> (.-Provider depth-context) {:value (inc depth)}
             [:r> (r/context-provider layout/geometry-context) #js {:value geometry}
              [:div.content-widget
              {:data-align (or (:align geometry) "left")
               :data-flow (or (:flow geometry) "block")
               :data-height-mode (if (and (:height geometry) (not (#{"auto" "fit"} (:height geometry)))) "fixed" "natural")
               :style (layout/style geometry)}
              (if (#{:main :markdown-components} (:owner entry))
                (into [component] args)
                ;; The enclosing authored boundary already owns viewport intent.
                ;; Acquire its chosen widget owner immediately rather than waiting
                ;; for a second observer inside retained SSR Suspense content.
                (loader/component-vector {:module (:owner entry)
                                          :view (keyword name)
                                          :load-on :immediate} args))]]])))
