(ns tolgraven.build.policy
  "Production source ownership checks use the resolved build graph, not caches.")

(defn- development-inspector? [{:keys [ns resource-name]}]
  (or (when ns
        (re-find #"^(day8\.re-frame-10x(?:\.|$)|re-frisk(?:-remote)?(?:\.|$)|tolgraven\.legacy-debug$)"
                 (str ns)))
      (when resource-name
        (re-find #"^(day8/re_frame_10x(?:/|\.cljs$)|re_frisk(?:_remote)?/|tolgraven/legacy_debug\.cljs$)"
                 resource-name))))

(defn no-debug-tools
  {:shadow.build/stage :compile-prepare}
  [state]
  (when (= :release (:mode state))
    (doseq [id (:build-sources state)
            :let [source (get-in state [:sources id])]
            :when (development-inspector? source)]
      (throw (ex-info "Development inspectors cannot enter a production build"
                      (select-keys source [:ns :resource-name])))))
  state)
