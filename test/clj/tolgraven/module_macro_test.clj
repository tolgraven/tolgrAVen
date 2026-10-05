(ns tolgraven.module-macro-test
  (:require [clojure.test :refer [deftest is]]
            [cljs.env :as env]
            [tolgraven.macros]))

(deftest browser-module-references-require-shadow-module-context
  (let [form '(shadow.lazy/loadable tolgraven.blog.module/spec)
        expand #(binding [env/*compiler* (atom %)]
                  (macroexpand-1 (list 'tolgraven.macros/browser-only form)))
        browser {:shadow.build/ns->mod {'tolgraven.blog.module :blog}}]
    (is (nil? (expand {})) "Standalone Codox analysis has no lazy-module graph")
    (is (= form (expand browser)) "Browser compilation retains lazy loadables")
    (is (nil? (expand (assoc browser :options
                            {:external-config {:tolgraven/ssr true}})))
        "SSR still excludes browser module references")))

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
    (is (= [:error-boundary [:appear "opacity"]] (:features options)))
    (is (:page options))
    (is (= [{:source :strapi :keys [:example]}] (:depends options)))))

(deftest lazy-view-expands-to-a-vector-selector
  (is (= '(tolgraven.loader/component-vector {:module :blog :view :post} [post])
         (macroexpand-1 '(tolgraven.macros/view {:module :blog :view :post} post))))
  (is (= '(tolgraven.loader/component-vector <example> [spec])
         (macroexpand-1 '(tolgraven.macros/view <example> spec)))))
