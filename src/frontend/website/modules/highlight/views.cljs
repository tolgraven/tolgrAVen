(ns website.modules.highlight.views
  (:require
    [clojure.string :as string]
    [website.component.registry]
    [website.component.font :as font]
    [website.macros :refer-macros [defc]]
    [website.react :as r]

    [website.modules.highlight.theme :as theme]
    ["lowlight" :as lowlight]
   ; ["react-syntax-highlighter/dist/esm/languages/hljs/clojure" :as clj-lang]
   ; ["react-syntax-highlighter/dist/esm/languages/hljs/javascript" :as js-lang]
    ))

(defc <font-pre>
  {:features [[:font font/fira]]}
  [{:keys [children] :as props}]
  [:pre (update (dissoc props :children) :style #(when % (js->clj % :keywordize-keys true))) children])

(defc <font-inline>
  {:features [[:font font/fira]]}
  [{:keys [children] :as props}]
  [:code (update (dissoc props :children) :style #(when % (js->clj % :keywordize-keys true))) children])

(def highlighter (.createLowlight lowlight (.-all lowlight)))

(def detection-languages
  ;; Highlight.js 11 treats any bare value (including "42") as a Properties
  ;; key. Keep neutral snippets neutral; explicit tags/owner subsets retain it.
  (into-array (remove #{"properties"} (array-seq (.listLanguages highlighter)))))

(def language-aliases
  ;; These fence tags share Clojure's grammar. Authoring retains the original tag.
  {"auto" nil
   "clj" "clojure"
   "cljs" "clojure"
   "cljc" "clojure"
   "clojurescript" "clojure"})

(defn- grammar-language [language]
  (get language-aliases language language))

(defn code-tree
  "Honor language tags; otherwise detect, using the owner's optional fallback
   only when detection produces no language. Unknown tags also try detection."
  [code language default-language & [auto-languages]]
  (let [language' (grammar-language language)
        default-language' (grammar-language default-language)
        auto-languages' (when auto-languages (vec (distinct (map grammar-language auto-languages))))
        explicit (when language'
                   (try (.highlight highlighter language' code)
                        (catch :default _ nil)))
        detected (or explicit (.highlightAuto highlighter code
                                           (if auto-languages'
                                             #js {:subset (into-array auto-languages')}
                                             #js {:subset detection-languages})))]
    (if (and (nil? (some-> detected .-data .-language)) default-language')
      (.highlight highlighter default-language' code)
      detected)))

(defn- token-runs
  "Flatten HAST spans into styled text runs without interpreting source HTML."
  [nodes stylesheet inherited]
  (mapcat
    (fn [node]
      (if (= "text" (.-type node))
        [[inherited (.-value node)]]
        (let [classes (some-> node .-properties .-className array-seq)
              style (reduce #(merge %1 (get stylesheet %2)) inherited classes)]
          (token-runs (array-seq (.-children node)) stylesheet style))))
    nodes))

(defn- coalesce-text [runs]
  ;; The current emitter may split punctuation from adjacent plain text. Keep
  ;; those in one shaping run, as with ordinary adjacent DOM text nodes.
  (reduce
    (fn [combined [style text :as run]]
      (if (and (empty? style) (seq combined) (empty? (first (peek combined))))
        (update-in combined [(dec (count combined)) 1] str text)
        (conj combined run)))
    [] runs))

(defn- code-lines [runs]
  (reduce
    (fn [lines [style text]]
      (let [parts (string/split text #"\n" -1)]
        (reduce-kv
          (fn [lines' index part]
            (let [last? (= index (dec (count parts)))
                  lines'' (update lines' (dec (count lines')) conj
                                  [style (str part (when-not last? "\n"))])]
              (if last? lines'' (conj lines'' []))))
          lines (vec parts))))
    [[]] runs))

(defn- line-attributes [line-props number]
  (let [props (when line-props (line-props number))]
    (if (map? props) props (js->clj props :keywordize-keys true))))

(defn- rendered-code [tree stylesheet numbered? starting-line-number line-props editable?]
  (let [runs (coalesce-text (token-runs (array-seq (.-children tree)) stylesheet {}))
        lines (code-lines runs)
        largest (+ starting-line-number (dec (count lines)))
        number-style (merge {:display "inline-block"
                             :minWidth (str (count (str largest)) ".25em")
                             :paddingRight "1em"
                             :textAlign "right"
                             :userSelect "none"}
                            (when editable?
                              {:width "var(--code-gutter)"
                               :minWidth 0
                               :textIndent 0}))
        tokens (fn [line]
                 (map-indexed (fn [index [style text]]
                                ^{:key index} [:span {:style style} text]) line))]
    (if (or numbered? line-props)
      (map-indexed
        (fn [index line]
          (let [number (+ starting-line-number index)
                props (line-attributes line-props number)
                props' (cond-> props
                         (and editable? numbered?)
                         (update :className str " code-block-edit-line"))]
            ^{:key index}
            (into [:span props']
                  (concat
                    (when numbered?
                      [[:span.comment.linenumber.react-syntax-highlighter-line-number
                        {:style number-style} number]])
                    (tokens line)))))
        lines)
      (tokens runs))))

(defc <formatted-code>
  "Full language support and Bruvbox are acquired only by code consumers."
  [code & {:keys [language default-language auto-languages style basic? inline? inline-language
                 line-numbers? starting-line-number line-props editable?]
           :or {basic? true
                style theme/bruvbox}}]
  (let [language' (or language (when inline? (or inline-language "bash")))
        numbered? (and (not inline?) (if (nil? line-numbers?) (not basic?) line-numbers?))
        ;; Retain the editable caret line without modifying authored source.
        display-code (if (and editable? (.endsWith code "\n")) (str code "\u200b") code)
        tree (r/use-memo #(code-tree display-code language' default-language auto-languages)
                         [display-code language' default-language auto-languages])
        stylesheet (r/use-memo #(js->clj style :keywordize-keys false) [style])
        selected-language (or (some-> tree .-data .-language) language' "text")
        contents (rendered-code tree stylesheet numbered? (or starting-line-number 1) line-props editable?)
        code-element (into [(if inline? :span :code)
                            {:class (str "language-" selected-language)
                             :style {:whiteSpace "pre"}}] contents)
        attrs {:style (cond-> (get stylesheet "hljs")
                        inline? (merge {:display "inline"
                                        :padding 0
                                        :background "transparent"}))
               :children (r/as-element code-element)}]
    (cond
      inline? [<font-inline> (assoc attrs :className "code-highlight")]
      editable? [:pre (dissoc attrs :children) code-element]
      :else [<font-pre> attrs])))
