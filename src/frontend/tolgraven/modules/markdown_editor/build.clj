(ns tolgraven.modules.markdown-editor.build
  "Select used public SDK exports without loading its unused CodeMirror barrel."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [tolgraven.build.modules :as modules]))

(def sdk-version "4.3.3")
(def entry-hash "f626a48218f8c489d1642290a8755bff83425203b54d3af799d40b45133cf253")
(def generated-root "target/generated-sdk")
(def generated-package "@site/mdxeditor")
(def gurx-exports #{"useCellValue" "useCellValues" "usePublisher" "useRealm"})

(defn digest [text]
  (let [bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes text java.nio.charset.StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 255 %)) bytes))))

(defn used-exports [sources]
  ;; The four owner adapters deliberately share this native alias. Keywords such
  ;; as :editor/editor are application identifiers, never SDK references.
  (set (map second (mapcat #(re-seq #"(?<![:\w-])editor/([A-Za-z_$][A-Za-z0-9_$]*)" %) sources))))

(defn entry-source [upstream exports prefix]
  (let [public (set (map string/trim (string/split (second (re-find #"(?s)export \{(.*?)\};" upstream)) #",")))
        imports (for [[_ names path] (re-seq #"import \{ ([^}]+) \} from \"([^\"]+)\";" upstream)
                      :let [selected (filter exports (map string/trim (string/split names #",")))]
                      :when (seq selected)]
                  {:names selected, :path path})
        found (into gurx-exports (mapcat :names imports))
        unknown (remove #(and (found %) (or (public %) (gurx-exports %))) exports)]
    (when (seq unknown)
      (throw (ex-info "An editor SDK export is not available in the pinned public entry" {:exports (vec unknown)})))
    (str "// Generated from the pinned public SDK entry. Do not edit.\n"
         (apply str (for [{:keys [names path]} imports]
                      (str "export { " (string/join ", " names) " } from "
                           (json/write-str (if (string/starts-with? path "./")
                                             (str prefix (subs path 2)) path)) ";\n")))
         (when-let [names (seq (sort (filter gurx-exports exports)))]
           (str "export { " (string/join ", " names) " } from \"@mdxeditor/gurx\";\n")))))

(defn prepare! []
  (let [package (io/file "node_modules/@mdxeditor/editor/package.json")
        installed (json/read-str (slurp package))
        entry (slurp (io/file (.getParentFile package) "dist/index.js"))]
    (when-not (and (= sdk-version (get installed "version")) (= entry-hash (digest entry)))
      (throw (ex-info "MDXEditor's public entry changed; review the selective adapter before building"
                      {:expected-version sdk-version})))
    (let [sources (map #(slurp (io/file "src/frontend/tolgraven/modules/markdown_editor" %))
                       ["views.cljs" "code.cljs" "source.cljs" "uploads.cljs"])
          output (entry-source entry (used-exports sources) "./dist/")
          directory (io/file generated-root generated-package)
          destination (io/file directory "entry.js")
          original (.toPath (.getCanonicalFile (io/file (.getParentFile package) "dist")))
          link (.toPath (io/file directory "dist"))]
      (io/make-parents destination)
      ;; Shadow's :file override has no npm package context for relative SDK
      ;; imports. A generated package supplies that context; its dist is only a
      ;; read-only reference to the original SDK, never a copied implementation.
      (when-not (java.nio.file.Files/exists link (make-array java.nio.file.LinkOption 0))
        (java.nio.file.Files/createSymbolicLink link original (make-array java.nio.file.attribute.FileAttribute 0)))
      (when-not (= original (java.nio.file.Files/readSymbolicLink link))
        (throw (ex-info "Generated SDK reference belongs to a different installation" {})))
      (spit (io/file directory "package.json")
            (json/write-str {:name generated-package, :version sdk-version, :type "module", :exports {"." "./entry.js"}}))
      (when (not= output (when (.isFile destination) (slurp destination)))
        (spit destination output)))))

(defn configure
  {:shadow.build/stage :configure}
  [state]
  (if (some #(= :markdown-editor (:id %)) (modules/discover))
    (do (prepare!)
        (reduce (fn [state' path]
                  (-> state'
                      (assoc-in (conj path :resolve "@mdxeditor/editor") {:target :npm, :require generated-package})
                      (update-in (conj path :js-package-dirs)
                                 #(vec (distinct (concat [generated-root] (or % ["node_modules"])))))))
                state [[:js-options] [:shadow.build/config :js-options]]))
    state))
