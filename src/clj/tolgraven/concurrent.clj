(ns tolgraven.concurrent
  "Java 21 task boundary: retain Clojure bindings, bound upstream concurrency,
   and keep blocking adapters off Undertow request threads."
  (:import [java.util.concurrent Executors ExecutorService Callable Future TimeUnit
            ExecutionException Semaphore]))

(defonce ^ExecutorService executor (Executors/newVirtualThreadPerTaskExecutor))
(defonce ^Semaphore upstream (Semaphore. 8 true))
(defonce ^Semaphore requests (Semaphore. 64 true))

(defn submit! [f]
  (let [work (bound-fn [] (f))]
    (.submit executor (reify Callable (call [_] (work))))))

(defn await!
  ([task] (await! task 30000))
  ([^Future task timeout-ms]
   (try (.get task timeout-ms TimeUnit/MILLISECONDS)
        (catch ExecutionException e (throw (.getCause e))))))

(defn upstream! [f]
  (when-not (.tryAcquire upstream 10000 TimeUnit/MILLISECONDS)
    (throw (ex-info "Upstream queue is busy" {:status 503})))
  (try (f) (finally (.release upstream))))

(defn map! [f values]
  (let [tasks (mapv #(submit! (fn [] (f %))) values)]
    (try (mapv await! tasks)
         (finally (doseq [^Future task tasks :when (not (.isDone task))] (.cancel task true))))))

(defn wrap-async [handler]
  (fn
    ([request] (handler request))
    ([request respond raise]
     (if (.tryAcquire requests)
       (do (submit! #(try (respond (handler request))
                          (catch Throwable error (raise error))
                          (finally (.release requests)))) nil)
       (respond {:status 503 :headers {"Retry-After" "1" "Content-Type" "text/plain"}
                 :body "Server busy. Please retry."})))))
