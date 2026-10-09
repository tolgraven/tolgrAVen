(ns util.process
  (:require [babashka.process :as process]))

(defn shell
  "Argument vectors, inherited output by default, and a bounded process tree."
  ([command] (shell {} command))
  ([options command]
   (let [child (process/process command (merge {:out :inherit :err :inherit}
                                              (dissoc options :timeout :continue)))
         waiting (future @child)
         result (if-let [timeout (:timeout options)] (deref waiting timeout ::timeout) @waiting)]
     (when (= ::timeout result)
       (try (process/destroy-tree child)
            (finally (process/destroy child) (future-cancel waiting)))
       (throw (ex-info "Command timed out; inspect its state before retrying" {})))
     (if (:continue options) result (process/check result)))))
