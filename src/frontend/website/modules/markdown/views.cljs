(ns website.modules.markdown.views
  (:require
    [website.component.registry]
    [website.macros :refer-macros [defc register-render-functions!]]
    [website.react :as r]
    [website.modules.highlight.code-block :as code-block]
    [website.components.image :as image]
    [website.media.schema :as media]
    [website.components.content.media :as authored-media]
    [website.modules.markdown-components.interface :as authored]
    ["react-markdown$default" :as ReactMarkdown]
    ["remark-gfm$default" :as remarkGfm]
    ["rehype-raw$default" :as rehypeRaw]
    ["unist-util-visit" :refer [visit]]))


(def options-schema
  [:?map
   [:?allow-images? :boolean]
   [:?image-assets [:?map-of :string media/asset]]
   [:?image-readiness [:maybe authored-media/readiness-schema]]
   [:?allow-raw? :boolean]
   [:?default-language :?string]
   [:?auto-languages [:?vector :string]]
   [:?code-options [:maybe code-block/options-schema]]])

(def block-context (r/create-context false))
(def link-context (r/create-context false))
(def options-context (r/create-context {}))

(def react-markdown (r/adapt-react-class ReactMarkdown))

(defn- native-arguments [{:keys [children] :as props} _ _]
  [(dissoc props :node :children) children])

(defn- code-arguments [{:keys [children className]} options {:keys [block? linked?]}]
  ;; SDK code children are text; native React children remain opaque elsewhere.
  (let [language (some->> className (re-find #"language-([\w-]+)") second)]
    [(if (string? children) children "")
     (merge (select-keys options [:default-language :auto-languages])
            (:code-options options)
            (cond-> {:inline? (not block?)}
              language (assoc :language language)
              linked? (assoc :copy? false)))]))

(defn- image-arguments [{:keys [src alt title]} {:keys [image-assets image-readiness]} _]
  (let [asset (get image-assets src)
        attrs (merge (authored-media/readiness-attrs src image-readiness)
                     (cond-> {:src (or (:src asset) src)
                              :alt (or alt (:alt asset) "")
                              :loading "lazy"}
                       (some? title) (assoc :title title)
                       asset (assoc :asset asset)))]
    [attrs]))

(defn- <link> [attrs children]
  [:a attrs [:r> (r/context-provider link-context) #js {:value true} children]])

(defn- <pre> [{:keys [node children] :as props}]
  (if (true? (some-> node (aget "data") (aget "tolgravenCodeBlock")))
    ;; Fenced code maps to the shared component, which owns its pre element.
    [:r> (r/context-provider block-context) #js {:value true} children]
    ;; Raw HTML retains its own attributes and whitespace semantics.
    [:pre (dissoc props :node :children) children]))

(def renderer-schema
  [:map
   [:component [:or :keyword fn? [:fn var?]]]
   [:arguments fn?]
   [:?visible? fn?]
   [:?key fn?]])

(def renderers
  "Markdown node -> rendered component, with optional argument/visibility adapters.
   Native elements retain rich children; code, media and widgets use shared UI."
  (merge
    (into {} (map (fn [tag] [tag {:component tag, :arguments native-arguments}]))
          [:h1 :h2 :h3 :h4 :h5 :h6 :p :blockquote :ul :ol :li
           :table :thead :tbody :tr :th :td :strong :em :del :u :sup :sub :mark
           :input :hr :br])
    {:code {:component #'code-block/<code-block>
            :arguments code-arguments}
     :pre {:component <pre>
           :arguments (fn [props _ _] [props])}
     :a {:component <link>
         :arguments native-arguments}
     :img {:component #'image/<picture>
           :arguments image-arguments
           :visible? (fn [{:keys [src]} {:keys [image-assets allow-images?]}]
                       (or (get image-assets src) allow-images?))
           :key (fn [{:keys [src]} {:keys [image-readiness]}]
                  (authored-media/preview-key src image-readiness))}
     :site-component {:component #'authored/<render>
                      :arguments (fn [{:keys [invocation]} _ _] [invocation])}}))

(defc ^:private <node>
  [renderer :- renderer-schema props :- :map]
  (let [options (r/use-context options-context)
        context {:block? (r/use-context block-context)
                 :linked? (r/use-context link-context)}
        {:keys [component arguments visible? key]} renderer]
    (when (or (nil? visible?) (visible? props options))
      (cond-> (into [(if (var? component) @component component)]
                    (arguments props options context))
        key (with-meta {:key (key props options)})))))

(def components
  ;; Build SDK component identities once. Options flow through normal context;
  ;; changes do not replace a renderer and remount its images or code controls.
  (into {} (map (fn [[tag {:keys [component] :as renderer}]]
                  ;; SDK tag keys are literal HTML names, including hyphens.
                  ;; Reagent camel-cases keyword prop keys at the React boundary.
                  [(name tag) (if (keyword? component)
                         (name component)
                         (r/reactify-component (fn [props] [<node> renderer props])))]))
        renderers))

(defn- mark-markdown-blocks []
  ;; Before rehype-raw, only Markdown code blocks have native pre nodes.
  ;; A private node type survives rehype-raw via passThrough. Authored HTML
  ;; cannot forge that type by setting an attribute.
  (fn [tree]
    (visit tree "element"
      (fn [node]
        (when (= "pre" (.-tagName node))
          (set! (.-type node) "tolgravenCodeBlock")
          nil)))))

(defn- restore-markdown-blocks []
  (fn [tree]
    (visit tree "tolgravenCodeBlock"
      (fn [node]
        (set! (.-type node) "element")
        (set! (.-data node) #js {:tolgravenCodeBlock true})
        nil))))

(defc <parse-markdown-components>
  "Parse markdown into pure React components using react-markdown"
  {:args-schema [:cat :?string [:? options-schema]]}
  [md-text & [{:keys [allow-raw? image-assets image-readiness] :as options}]]
  [:r> (r/context-provider options-context) #js {:value (or options {})}
   [:r> (r/context-provider authored-media/readiness-context) #js {:value image-readiness}
     [:r> (r/context-provider authored-media/asset-context) #js {:value (or image-assets {})}
     [react-markdown
     {:children md-text
      :remarkPlugins [remarkGfm authored/plugin]
      :rehypePlugins (cond-> [mark-markdown-blocks]
                       allow-raw? (conj [rehypeRaw {:passThrough ["tolgravenCodeBlock"]}])
                       true (conj restore-markdown-blocks))
      :components components}]]]])

(register-render-functions!)
