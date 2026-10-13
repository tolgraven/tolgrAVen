(ns tolgraven.markdown-components-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [tolgraven.modules.markdown-editor.views :as rich]
            [tolgraven.test-support :as support]
            [tolgraven.ssr.render :as render]
            [tolgraven.markdown-editor-demo]
            [clojure.string :as string]
            [reagent.dom.server :as server]
            [tolgraven.components.markdown-components :as tags]
            [tolgraven.components.ui.code :as public-markdown]
            [tolgraven.modules.markdown-editor.catalog :as catalog]
            [tolgraven.markdown.authoring :as model]
            [tolgraven.components.content.markdown :as markdown]
            [tolgraven.components.content.box :as box]
            [tolgraven.components.content.frame :as frame]
            [tolgraven.components.content.layout :as layout]
            [tolgraven.components.content.resize :as resize]
            [tolgraven.components.code-block :as code-block]
            [tolgraven.react :as r]
            [tolgraven.blog-editor-test :as editor-test]
            [re-frame.db :as rfdb]
            [tolgraven.content.schema :as content-schema]
            [tolgraven.validation :as validation]
            ["mdast-util-from-markdown" :refer [fromMarkdown]]))

(deftest canonical-export-inventory-survives-json-with-its-namespace
  (let [inventory (render/export-inventory {:settings #{:view :tolgraven.modules.settings.views/<config-table>}})
        restored (js->clj (js/JSON.parse (js/JSON.stringify (clj->js inventory))) :keywordize-keys true)]
    (is (= #{"view" "tolgraven.modules.settings.views/<config-table>"} (set (:settings restored))))))

(deftest metadata-search-includes-docstrings-and-types
  (is (some #(= "tolgraven.components.content.box/<highlight-box>" (:name %)) (catalog/search "aside warning")))
  (is (some #(= "tolgraven.modules.settings.views/<config-table>" (:name %)) (catalog/search "theme")))
  (is (= {:key :title, :optional? true, :schema :string}
         (second (catalog/arguments (get catalog/by-name "tolgraven.components.content.box/<highlight-box>"))))))

(deftest argument-help-expands-the-shared-nullable-schema-dsl
  (is (= [[:text {:optional true} :string]]
         (vec (catalog/map-fields [:?map [:?text :string]]))))
  (is (= [[:text :string]]
         (vec (catalog/map-fields [:cat {:title "Arguments"} [:map [:text :string]]])))))

(deftest data-arguments-round-trip-without-evaluation
  (doseq [value [{:literal "Hello"} {:literal 2} {:literal false}
                 {:literal ["One" "Two"]} {:literal {:text "A", :checked true}}
                 {:literal "db:not-a-path"} {:literal "edn:literal text"}
                 {:literal :content} {:literal #{:appear :seen}} {:literal [:options :theme]}
                 {:literal {:content/title "Typed key"}} {:literal {"string-key" 1}} {:literal {1 "Numeric key"}}
                 {:path [:content :blog "post-id" :title]}]]
    (is (= value (model/decode-value (model/encode-value value)))))
  (is (= {:path [:options :theme :dark-mode]}
         (model/decode-value "db:[:options :theme :dark-mode]"))))

(deftest private-state-and-code-cannot-become-arguments
  (doseq [text ["edn:#=(js/alert 1)" "edn:#inst \"2026-10-13\"" "edn:#uuid \"00000000-0000-0000-0000-000000000000\""]]
    (is (thrown? js/Error (model/decode-value text))))
  (doseq [text ["db:[:user :active-user]" "db:[:supabase :client]"
                "db:[:options :secret]" "db:[:state :ops]" "db:#=(do (js/alert 1))"]]
    (is (thrown? js/Error (model/decode-value text))))
  (doseq [source ["<Component component=\"tolgraven.components.content.markdown/<heading>\" level={2} text=\"Bad\" />"
                  "<Component component=\"tolgraven.components.content.markdown/<heading>\" {...anything} />"
                  "<Component component=\"tolgraven.components.content.markdown/<heading>\" text=\"One\" text=\"Two\" />"
                  "<Component component=\"tolgraven.components.content.markdown/<heading>\" text={alert(1)} />"
                  "<Component component=\"tolgraven.components.content.markdown/<heading>\" text=\"Bad\">Children</Component>"]]
    (is (:error (tags/parse-tag source)))))

(deftest recognized-markup-keeps-block-shape-and-prose
  (let [tree (fromMarkdown "Before {literal}\n\n<Component component=\"tolgraven.components.content.box/<highlight-box>\" text=\"Hello\" />\n\nAfter")]
    ((tags/plugin) tree)
    (is (= "Before {literal}" (.. tree -children (at 0) -children (at 0) -value)))
    (is (= "site-component" (.. tree -children (at 1) -data -hName)))
    (is (= 0 (alength (.. tree -children (at 1) -children))))
    (is (= "After" (.. tree -children (at 2) -children (at 0) -value))))
  (let [tree (fromMarkdown "Inline <Component component=\"tolgraven.components.content.markdown/<heading>\" level=\"json:2\" text=\"No\" /> text")]
    ((tags/plugin) tree)
    (is (= "text" (.. tree -children (at 0) -children (at 1) -type))))
  (let [tree (fromMarkdown "```md\n<Component component=\"tolgraven.components.content.markdown/<heading>\" text=\"Literal\" />\n```\n\n<div>Raw</div>")]
    ((tags/plugin) tree)
    (is (= "code" (.. tree -children (at 0) -type)))
    (is (= "html" (.. tree -children (at 1) -type)))))

(deftest semantic-editor-marks-preserve-the-normal-html-boundary
  (let [tree (fromMarkdown "<u>Under **bold**</u> and <sup>2</sup> <u onclick='bad()'>unsafe</u>")]
    ((tags/plugin) tree)
    (is (= "u" (.. tree -children (at 0) -children (at 0) -data -hName)))
    (is (= "strong" (.. tree -children (at 0) -children (at 0) -children (at 1) -type)))
    (is (some #(= "html" (.-type %)) (array-seq (.. tree -children (at 0) -children))))))

(deftest iframe-size-messages-bind-origin-and-exact-window
  (let [window #js {}
        frame #js {:contentWindow window}
        event #js {:origin "null", :source window, :data #js {:type "website:iframe-size", :height 224}}]
    (is (frame/valid-size-message? event frame "null"))
    (is (not (frame/valid-size-message? event frame "https://unexpected.test")))
    (is (not (frame/valid-size-message? event #js {:contentWindow #js {}} "null")))
    (doseq [height [0 -1 10000 js/Infinity "224"]]
      (is (not (frame/valid-size-message? #js {:origin "null", :source window
                                              :data #js {:type "website:iframe-size", :height height}}
                                        frame "null"))))))

(deftest persisted-geometry-retains-chosen-units-and-component-fields
  (is (= {:width "21rem"}
         (resize/edge-geometry :right {:width "20rem"}
                               {:width 320, :height 160, :units {:rem 16, :em 20, :parent 800}} 16 0))
      "Resizing one edge must not introduce a present nil size")
  (is (= {:width "20rem", :flow "left"}
         (layout/geometry {:width 640, :layout-width "20rem", :layout-flow "left"})))
  (is (= "50%" (layout/resized "40%" 400 {:rem 16, :em 20, :parent 800})))
  (is (= {:width "21rem", :height "11rem"}
         (resize/geometry {:width "20rem", :height "10rem"}
                          {:width 320, :height 160, :units {:rem 16, :em 20, :parent 800}} 16 16))))

(deftest runtime-language-suggestions-have-the-shared-cms-contract
  (is (nil? (validation/explain content-schema/code-languages [])))
  (is (nil? (validation/explain content-schema/code-languages [{:value "cljs", :label "ClojureScript"}])))
  (doseq [invalid [[{:value "", :label "No blank auto entry"}]
                   [{:value "bad token", :label "Invalid"}]
                   [{:value "cljs", :label "   "}]
                   [{:value "cljs", :label "First"} {:value "cljs", :label "Second"}]]]
    (is (some? (validation/explain content-schema/code-languages invalid)))))

(deftest reusable-builtins-render-native-markup
  (is (= "<h2>Section</h2>" (server/render-to-static-markup [markdown/<heading> {:level 2, :text "Section"}])))
  (is (re-find #"<aside" (server/render-to-static-markup [box/<highlight-box> {:text "Note", :tone "tip"}]))))

(deftest rich-editor-preserves-formatting-and-custom-node-on-round-trip
  (async done
    (let [element (.createElement js/document "div")
          source "## Heading\n\n**Bold** and *italic*.\n\n- [ ] Task\n- [x] Done\n\n<Component component=\"tolgraven.components.content.box/<highlight-box>\" text=\"An aside\" tone=\"tip\" />\n"
          *changes (atom [])
          *root (atom nil)]
      (.appendChild (.-body js/document) element)
      (-> (support/create-root! element)
          (.then (fn [root]
                   (reset! *root root)
                   (support/render! root [rich/<editor> {:text source
                                                       :on-change #(swap! *changes conj %)}])))
          (.then (fn [_] (support/wait-for! #(.querySelector element "[contenteditable=true]"))))
          (.then (fn [_]
                   (is (.querySelector element "h2"))
                   (is (.querySelector element "strong"))
                   (is (.querySelector element ".md-component-node"))
                   (is (.querySelector element "[role=checkbox]"))
                   (is (empty? @*changes) "Initial SDK normalization must not overwrite saved Markdown")
                   (let [argument (.querySelector element "input[aria-label='tolgraven.components.content.box/<highlight-box> text']")]
                     (is (= "An aside" (.-value argument)))
                     ;; Native input setup is test-only; the application uses Reagent event props.
                     (let [setter (.-set (.getOwnPropertyDescriptor js/Object (.-prototype js/HTMLInputElement) "value"))]
                       (.call setter argument "A changed aside"))
                     (.dispatchEvent argument (js/Event. "input" #js {:bubbles true})))))
          (.then (fn [_] (support/wait-for! #(seq @*changes))))
          (.then (fn [_]
                   (let [markdown (last @*changes)]
                     (is (string/includes? markdown "A changed aside"))
                     (is (string/includes? markdown "## Heading"))
                     (is (string/includes? markdown "**Bold**"))
                     (is (re-find #"(?:-|\*) \[x\] Done" markdown)))))
          (.catch #(is false (str %)))
          (.finally (fn [] (when @*root (support/unmount! @*root)) (.remove element) (done)))))))

(deftest rich-code-block-uses-the-normal-component-and-writes-native-code-metadata
  (async done
    (let [element (.createElement js/document "div")
          *changes (atom [])
          *root (atom nil)
          change! (fn [selector value]
                    (let [input (.querySelector element selector)
                          prototype (if (= "TEXTAREA" (.-tagName input))
                                      (.-prototype js/HTMLTextAreaElement) (.-prototype js/HTMLInputElement))
                          setter (.-set (.getOwnPropertyDescriptor js/Object prototype "value"))]
                      (.call setter input value)
                      (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))))]
      (.setAttribute element "class" "blog-image-editor")
      (.appendChild (.-body js/document) element)
      (-> (support/create-root! element)
          (.then (fn [root]
                   (reset! *root root)
                   (support/render! root [rich/<editor> {:text "```cljs sample\n(defn yada [] [])\n```"
                                                       :on-change #(swap! *changes conj %)}])))
          (.then (fn [_] (support/wait-for! #(.querySelector element ".code-editor-fields textarea"))))
          (.then (fn [_]
                   (is (.querySelector element ".code-block .code-wrap"))
                   (is (nil? (.querySelector element ".cm-editor")) "Fenced blocks use the ordinary site UI")
                   (is (< (.-height (.getBoundingClientRect (.querySelector element ".code-editor-fields textarea")))
                          (* 0.4 (.-innerHeight js/window)))
                       "Public blog CSS must not impose its old source-editor minimum on nested code fields")
                   (change! ".code-editor-fields textarea" "(+ 3 4)")))
          (.then (fn [_] (support/wait-for! #(string/includes? (or (last @*changes) "") "(+ 3 4)"))))
          (.then (fn [_]
                   (is (string/includes? (last @*changes) "```cljs sample") "Formatting aliases never rewrite the authored fence tag")
                   (change! ".code-editor-fields label:nth-of-type(2) input" "javascript")))
          (.then (fn [_] (support/wait-for! #(.querySelector element ".search-select [role=option]"))))
          (.then (fn [_] (.click (.querySelector element ".search-select [role=option]"))))
          (.then (fn [_] (support/wait-for! #(string/includes? (or (last @*changes) "") "```javascript"))))
          (.then (fn [_] (change! ".code-editor-fields label:nth-of-type(3) input" "title=Example")))
          (.then (fn [_] (support/wait-for! #(string/includes? (or (last @*changes) "") "javascript title=Example"))))
          (.then (fn [_] (is (string/includes? (last @*changes) "(+ 3 4)"))))
          (.catch #(is false (str %)))
          (.finally (fn [] (when @*root (support/unmount! @*root)) (.remove element) (done)))))))

(deftest code-language-picker-observes-runtime-config-and-empty-means-auto-only
  (async done
    (let [before @rfdb/app-db
          element (.createElement js/document "div")
          *root (atom nil)
          *chosen (atom nil)]
      (r/dispatch-sync [:content [:common :code-languages] [{:value "rust", :label "Rust"}]])
      (.appendChild (.-body js/document) element)
      (-> (support/create-root! element)
          (.then (fn [root] (reset! *root root)
                   (support/render! root [code-block/<code-block> "fn main() {}"
                                          {:editable? true, :language "", :on-language-change #(reset! *chosen %)}])))
          (.then (fn [_] (support/wait-for! #(.querySelector element "[role=combobox]"))))
          (.then (fn [_]
                   (.focus (.querySelector element "[role=combobox]"))
                   (support/settle!)))
          (.then (fn [_]
                   (is (= ["Auto detect" "Rust"] (mapv #(.-textContent %) (array-seq (.querySelectorAll element "[role=option]")))))
                   (r/dispatch-sync [:content [:common :code-languages] []])
                   (support/settle!)))
          (.then (fn [_]
                   (is (= ["Auto detect"] (mapv #(.-textContent %) (array-seq (.querySelectorAll element "[role=option]")))))
                   (.dispatchEvent (.querySelector element "[role=combobox]")
                                   (js/KeyboardEvent. "keydown" #js {:key "Enter", :bubbles true, :cancelable true}))
                   (is (= "" @*chosen))))
          (.catch #(is false (str %)))
          (.finally (fn [] (when @*root (support/unmount! @*root))
                      (.remove element) (editor-test/restore-fixture-db! before) (done)))))))

(deftest published-components-render-real-interactive-ui
  (async done
    (let [element (.createElement js/document "div")
          source "Before\n\n<Component component=\"tolgraven.components.content.box/<highlight-box>\" text=\"Published aside\" tone=\"tip\" layout-width=\"20rem\" layout-flow=\"right\" />\n\nAfter\n\n<Component component=\"tolgraven.modules.settings.views/<config-table>\" title=\"Appearance controls\" />"
          *root (atom nil)]
      (.appendChild (.-body js/document) element)
      (-> (support/create-root! element)
          (.then (fn [root]
                   (reset! *root root)
                   (support/render! root [public-markdown/<parse-markdown-components> source])))
          (.then (fn [_] (support/wait-for! #(and (.querySelector element ".content-box")
                                                (.querySelector element "[role=switch]")))))
          (.then (fn [_]
                   (is (= "Published aside" (.-textContent (.querySelector element ".content-box p"))))
                   (is (= "right" (.. element (querySelector ".content-widget") -style -float)))
                   (is (.querySelector element "[role=switch]"))
                   (is (nil? (.querySelector element "site-component")) "No inert custom HTML tags remain")))
          (.catch #(is false (str %)))
          (.finally (fn [] (when @*root (support/unmount! @*root)) (.remove element) (done)))))))
