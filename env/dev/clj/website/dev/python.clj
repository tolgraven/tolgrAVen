(ns website.dev.python
  "Prepare checkout-local codec tools before development content startup."
  (:require [clojure.java.shell :as shell]))

(defn ensure-ready! []
  (let [{:keys [exit out err]} (shell/sh "python3" "scripts/dev/python.py")]
    (when-not (zero? exit)
      (throw (ex-info "Development Python setup failed. Run bb dev:setup."
                      {:exit exit, :error err})))
    (print out)))
