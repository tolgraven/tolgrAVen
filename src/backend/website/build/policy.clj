(ns website.build.policy
  "Production source ownership checks use the resolved build graph, not caches.")

(defn- development-inspector? [{:keys [ns resource-name]}]
  (or (when ns
        (re-find #"^(day8\.re-frame-10x(?:\.|$)|re-frisk(?:-remote)?(?:\.|$)|website\.legacy-debug$|website\.(?:dev-console|modules\.dev-console)(?:\.|$))"
                 (str ns)))
      (when resource-name
        (re-find #"^(day8/re_frame_10x(?:/|\.cljs$)|re_frisk(?:_remote)?/|website/legacy_debug\.cljs$|website/(?:dev_console|modules/dev_console)/)"
                 resource-name))))

(defn no-debug-tools
  {:shadow.build/stage :compile-prepare}
  [state]
  (when (= :release (:mode state))
    (let [defines (get-in state [:compiler-options :closure-defines])]
      (when (or (true? (get defines 'goog.DEBUG))
                (true? (get defines "goog.DEBUG"))
                (true? (get defines 're-frame.trace/trace-enabled?))
                (true? (get defines "re_frame.trace.trace_enabled_QMARK_")))
        (throw (ex-info "Production builds cannot enable debug instrumentation" {}))))
    (doseq [id (:build-sources state)
            :let [source (get-in state [:sources id])]
            :when (development-inspector? source)]
      (throw (ex-info "Development inspectors cannot enter a production build"
                      (select-keys source [:ns :resource-name])))))
  state)
