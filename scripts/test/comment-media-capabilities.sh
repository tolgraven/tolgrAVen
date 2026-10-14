#!/usr/bin/env bash
set -euo pipefail
# Run through tolgraven-cpu; only its isolated configuration is modified.
original=$(mktemp)
had_overrides=false
if test -f resources/site.overrides.edn; then had_overrides=true; cp resources/site.overrides.edn "$original"; fi
restore_selection() {
  if "$had_overrides"; then cp "$original" resources/site.overrides.edn
  else rm -f resources/site.overrides.edn; fi
  rm -f "$original"
}
trap restore_selection EXIT
bb -e '(require (quote clojure.edn)) (let [p "resources/site.overrides.edn" before (if (.exists (java.io.File. p)) (clojure.edn/read-string (slurp p)) {:modules {}}) disabled [:user :blog :chat :gpt :search :strava :instagram]] (spit p (pr-str (reduce #(assoc-in %1 [:modules %2 :enabled] false) before disabled))))'
lein with-profile +provided run -m clojure.main -e '(require (quote website.media.pipeline) (quote website.modules.main.api)) (assert (nil? (ns-resolve (quote website.media.pipeline) (quote comment-upload!)))) (assert (not-any? #(= "/supabase/comment-media" (first %)) (website.modules.main.api/routes))) (println "PASS: disabled User prunes comment upload implementation and route")'
