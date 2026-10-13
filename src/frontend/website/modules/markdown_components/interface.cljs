(ns website.modules.markdown-components.interface
  "Recognize authored public tags in Markdown HTML nodes without changing prose parsing."
  (:require [clojure.string :as string]
            [website.loader :as loader]
            [website.render-context :as context]
            [website.markdown.authoring :as model]
            [website.component.public-catalog :as catalog]
            ["mdast-util-from-markdown" :refer [fromMarkdown]]
            ["mdast-util-mdx-jsx" :refer [mdxJsxFromMarkdown]]
            ["micromark-extension-mdx-jsx" :refer [mdxJsx]]
            ["unist-util-visit" :refer [visit]]))

(defn parse-tag [source]
  (try
    (let [tree (fromMarkdown source #js {:extensions #js [(mdxJsx)]
                                        :mdastExtensions #js [(mdxJsxFromMarkdown)]})
          nodes (tree-seq #(some? (.-children %)) #(array-seq (.-children %)) tree)
          tags (filter #(contains? #{"mdxJsxFlowElement" "mdxJsxTextElement"} (.-type %)) nodes)]
      (when (= 1 (count tags))
        (let [tag (first tags)
              start (some-> tag .-position .-start .-offset)
              end (some-> tag .-position .-end .-offset)]
          (if (= (string/trim source) (string/trim (subs source start end)))
            (model/invocation (js->clj tag :keywordize-keys true))
            {:error "Put each component on a separate line"}))))
    (catch :default _ {:error "Invalid component markup; use quoted data arguments"})))

(def semantic-tags #{"u" "sup" "sub" "mark"})

(defn semantic-children
  "Convert only balanced, attribute-free semantic marks; ordinary HTML retains its trust boundary."
  [children]
  (loop [todo (seq children), stack [{:children []}]]
    (if-let [node (first todo)]
      (let [[_ closing tag] (when (= "html" (.-type node))
                             (re-matches #"<(/?)(u|sup|sub|mark)>" (.-value node)))]
        (cond
          (and tag (empty? closing))
          (recur (next todo) (conj stack {:tag tag, :children []}))

          (and tag (= "/" closing) (> (count stack) 1) (= tag (:tag (peek stack))))
          (let [frame (peek stack)
                stack' (pop stack)
                mark #js {:type "authoredMark", :data #js {:hName tag}, :children (into-array (:children frame))}]
            (recur (next todo) (update-in stack' [(dec (count stack')) :children] conj mark)))

          (and tag (> (count stack) 1)) children
          :else (recur (next todo) (update-in stack [(dec (count stack)) :children] conj node))))
      (if (= 1 (count stack)) (:children (first stack)) children))))

(defn restore-semantic-marks! [tree]
  (visit tree
    (fn [node]
      (when-let [children (.-children node)]
        (set! (.-children node) (into-array (semantic-children (array-seq children))))))))

(defn plugin []
  (fn [tree]
    (restore-semantic-marks! tree)
    (visit tree "html"
      (fn [node _ parent]
        (when-let [[_ name] (re-find #"^\s*<([A-Z][A-Za-z0-9]*)[\s/>]" (.-value node))]
          ;; Unknown ordinary/raw HTML keeps its existing ownership and trust policy.
          (when (= "Component" name)
            (let [invocation (or (parse-tag (.-value node)) {:error "Use one self-closing component tag"})
                  only-child? (and (= "paragraph" (.-type parent)) (= 1 (alength (.-children parent))))
                  block? (or only-child? (not= "paragraph" (.-type parent)))
                  target (if only-child? parent node)
                  wire (if (:error invocation) invocation
                           {:name "Component"
                            :attributes (model/node-attributes invocation)})]
              (if block?
                (do
                  (set! (.-type target) "paragraph")
                  ;; Block widgets cannot sit inside a native paragraph: HTML repair
                  ;; would otherwise replace the SSR nodes before hydration.
                  (set! (.-children target) #js [])
                  (set! (.-data target)
                        #js {:hName "site-component"
                             :hProperties #js {:invocation (js/JSON.stringify (clj->js wire))}}))
                (do
                  (set! (.-type node) "text")
                  (set! (.-value node) "[Put each component on a separate line]"))))))
        nil))))

(defn <render> [invocation]
  (let [node (try (js->clj (js/JSON.parse invocation) :keywordize-keys true)
                  (catch :default _ {:error "Invalid component arguments"}))
        invocation' (if (:error node) node (model/invocation node))]
    (if (contains? (if context/*server?* context/*modules* loader/modules) :markdown-components)
      (loader/component-vector :markdown-components/render [invocation'])
      [:aside.content-component-error {:role "status"}
       "Components are unavailable on this site"])))
