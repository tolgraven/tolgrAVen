(ns tolgraven.module-macro-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [tolgraven.macros]))

(deftest browser-module-references-use-reader-features
  (let [modules (fn [features]
                  (with-open [reader (java.io.PushbackReader.
                                      (io/reader "src/cljc/tolgraven/loader/code.cljc"))]
                    (let [options {:read-cond :allow :features features}]
                      (read options reader)
                      (nth (read options reader) 2))))]
    (is (= {} (modules #{:cljs})) "Standalone Codox excludes browser lazy modules")
    (is (= {} (modules #{:cljs :ssr :node})) "SSR uses its eager module graph")
    (is (some #{'m/make-modules} (tree-seq coll? seq (modules #{:cljs :browser})))
        "Browser builds retain the Shadow loadables")))

(deftest loading-helper-respects-component-bindings
  (let [expand #(macroexpand-1 (cons 'tolgraven.macros/defc %))
        injected? #(some #{'tolgraven.component/loading-view}
                         (tree-seq coll? seq (expand %)))]
    (is (injected? '(<example> [] [<loading>])))
    (is (not (injected? '(<example> [url <loading>] [:div <loading>]))))
    (is (not (injected? '(<example> [{:keys [<loading>]}] [:div <loading>]))))
    (is (not (injected? '(<example> [] :let [<loading> [:p "Custom"]] [:div <loading>]))))))

(deftest page-declarations-cannot-disable-their-boundary
  (let [expanded (macroexpand-1
                   '(tolgraven.macros/defpage <example> "Example"
                      {:features [[:error-boundary false] [:appear "opacity"]]
                       :depends [{:source :strapi :keys [:example]}]}
                      [] [:section "Example"]))
        options (nth expanded 3)]
    (is (= 'tolgraven.macros/defc (first expanded)))
    (is (= [[:error-boundary false] [:appear "opacity"]] (:features options))
        "defpage leaves feature interpretation to defc")
    (is (some #(and (map? %) (= [:error-boundary [:appear "opacity"]] (:features %)))
              (tree-seq coll? seq (macroexpand-1 expanded)))
        "defc enforces the page boundary without duplicating declaration parsing")
    (is (:page options))
    (is (= [{:source :strapi :keys [:example]}] (:depends options)))))

(deftest lazy-view-expands-to-a-vector-selector
  (is (= '(tolgraven.loader/component-vector {:module :blog :view :post} [post])
         (macroexpand-1 '(tolgraven.macros/<> {:module :blog :view :post} post))))
  (is (= '(tolgraven.loader/component-vector :blog/post [post])
         (macroexpand-1 '(tolgraven.macros/<> :blog/post post))))
  (is (= '(tolgraven.loader/component-vector <example> [spec])
         (macroexpand-1 '(tolgraven.macros/<> <example> spec)))))
