(ns tolgraven.markdown-editor-build-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [tolgraven.modules.markdown-editor.build :as build]))

(def public-entry
  (str "export * from \"@mdxeditor/gurx\";\n"
       "import { MDXEditor } from \"./MDXEditor.js\";\n"
       "import { rootEditor$, unused$ } from \"./plugins/core/index.js\";\n"
       "import { codeMirrorPlugin } from \"./plugins/codemirror/index.js\";\n"
       "export { MDXEditor, rootEditor$, unused$, codeMirrorPlugin };\n"))

(deftest only-used-public-exports-reach-the-generated-entry
  (let [used (build/used-exports ["[editor/MDXEditor {:editor/editor true}] (editor/useRealm) editor/rootEditor$"])
        entry (build/entry-source public-entry used "../../sdk/")]
    (is (= #{"MDXEditor" "useRealm" "rootEditor$"} used))
    (is (= "../../sdk/MDXEditor.js"
           (json/read-str (second (re-find #"MDXEditor \} from (\"[^\"]+\")" entry)))))
    (is (string/includes? entry "rootEditor$"))
    (is (string/includes? entry "from \"@mdxeditor/gurx\""))
    (is (not (string/includes? entry "codeMirror")))
    (is (not (string/includes? entry "unused$")))
    (is (not (string/includes? entry "export *")))))

(deftest changed-or-private-sdk-exports-fail-explicitly
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pinned public entry"
                       (build/entry-source public-entry #{"privateHelper"} "../../sdk/")))
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (build/digest ""))))

(deftest live-watch-export-changes-fail-with-the-reconfiguration-action
  (is (nil? (build/require-current-exports! #{"MDXEditor"} #{"MDXEditor"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Restart this Shadow watch"
                       (build/require-current-exports! #{"MDXEditor"} #{"MDXEditor" "InsertTable"}))))
