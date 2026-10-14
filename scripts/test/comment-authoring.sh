#!/usr/bin/env bash
set -euo pipefail
# Run through tolgraven-cpu: this edits only its isolated snapshot.
original_site=$(mktemp)
original_overrides=$(mktemp)
cp resources/site.edn "$original_site"
had_overrides=false
if test -f resources/site.overrides.edn; then
  had_overrides=true
  cp resources/site.overrides.edn "$original_overrides"
fi
restore_selection() {
  cp "$original_site" resources/site.edn
  if "$had_overrides"; then cp "$original_overrides" resources/site.overrides.edn
  else rm -f resources/site.overrides.edn; fi
  rm -f "$original_site" "$original_overrides"
}
trap restore_selection EXIT
bb -e '(require (quote clojure.edn)) (let [p "resources/site.overrides.edn" before (if (.exists (java.io.File. p)) (clojure.edn/read-string (slurp p)) {:modules {}})] (spit p (pr-str (assoc-in before [:modules :blog-editor :enabled] false))))'
npm run build
lein with-profile +provided run -m shadow.cljs.devtools.cli compile app-dev
bb -e '(require (quote clojure.edn) (quote clojure.string)) (let [manifest (clojure.edn/read-string (slurp "resources/public/js/compiled/out/manifest.edn")) sources (mapcat :sources manifest) ids (set (map :module-id manifest))] (assert (ids :markdown-editor)) (assert (not (ids :blog-editor))) (assert (not-any? #(clojure.string/starts-with? % "website/modules/blog_editor/") sources)) (assert (some #{"website/modules/markdown_editor/authoring.cljs"} sources)) (println "PASS: comment-only browser graph excludes the post authoring owner"))'
test -f resources/public/css/website/modules/markdown-editor.min.css
bb -e '(require (quote website.build.modules)) (let [styles (website.build.modules/style-definitions) paths (mapcat :paths (vals styles))] (assert (get styles :markdown-editor)) (assert (not (get styles :blog-editor))) (assert (not (some #{"/css/website/modules/blog-editor.min.css"} paths))) (println "PASS: selected style inventory excludes the post authoring sheet"))'
