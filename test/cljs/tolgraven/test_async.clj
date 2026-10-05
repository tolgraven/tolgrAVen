(ns tolgraven.test-async)

;; core.async is already a project dependency. This small bridge lets existing
;; Promise-based test adapters await Reagent lifecycle work without blocking JS.
(defmacro go-promise [& body]
  `(js/Promise.
    (fn [resolve# reject#]
      (cljs.core.async/go
        (try (resolve# (do ~@body))
             (catch :default error# (reject# error#)))))))

(defmacro await! [promise]
  `(let [result# (cljs.core.async/<! (tolgraven.test-support/result-channel ~promise))]
     (if-let [error# (:error result#)] (throw error#) (:value result#))))
