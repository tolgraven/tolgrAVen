(ns website.build-policy-test
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [website.build.policy :as policy]))

(deftest resolved-release-sources-exclude-development-inspectors
  (doseq [source [{:ns 'day8.re-frame-10x}
                  {:ns 'day8.re-frame-10x.preload}
                  {:ns 're-frisk.core}
                  {:ns 're-frisk-remote.core}
                  {:ns 'website.legacy-debug}
                  {:ns 'website.inspector.stack}
                  {:ns 'website.modules.dev-console.views}
                  {:ns 'website.dev-console.capture}
                  {:ns 'website.modules.dev-console.module}
                  {:resource-name "day8/re_frame_10x.cljs"}
                  {:resource-name "day8/re_frame_10x/panels/app_db.cljs"}
                  {:resource-name "re_frisk/core.cljs"}
                  {:resource-name "re_frisk_remote/core.cljs"}
                  {:resource-name "website/inspector/stack.cljs"}
                  {:resource-name "website/modules/dev_console/views.cljs"}
                  {:resource-name "website/modules/dev_console/module.cljs"}]]
    (let [state {:mode :release
                 :build-sources [:active]
                 :sources {:active source}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot enter a production"
                           (policy/no-debug-tools state)))
      (is (= (assoc state :mode :dev)
             (policy/no-debug-tools (assoc state :mode :dev)))))))

(deftest ordinary-production-effects-and-unused-cache-entries-are-allowed
  (let [state {:mode :release
               :build-sources [:http :diagnostics]
               :sources {:http {:ns 'website.http
                                :resource-name "website/http.cljs"}
                         :diagnostics {:ns 'website.diagnostics.stack
                                       :resource-name "website/diagnostics/stack.cljc"}
                         :old {:ns 'day8.re-frame-10x}}}]
    (is (identical? state (policy/no-debug-tools state)))))

(deftest release-rejects-debug-and-trace-overrides
  (doseq [defines [{'goog.DEBUG true}
                   {"goog.DEBUG" true}
                   {'re-frame.trace/trace-enabled? true}
                   {"re_frame.trace.trace_enabled_QMARK_" true}]]
    (let [state {:mode :release
                 :compiler-options {:closure-defines defines}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"debug instrumentation"
                           (policy/no-debug-tools state)))
      (is (= (assoc state :mode :dev)
             (policy/no-debug-tools (assoc state :mode :dev)))))))

(deftest ssr-worker-keeps-browser-transports-offline
  (let [preamble (get-in (edn/read-string (slurp "shadow-cljs.edn")) [:builds :ssr :prepend])
        program (str "let deliveries = 0;
                      globalThis.fetch = async () => { deliveries++; };
                      globalThis.XMLHttpRequest = class { constructor() { deliveries++; } };
"
                     preamble
                     "
const assert = require('node:assert/strict');
                      (async () => {
                        await assert.rejects(fetch('https://example.test/data'), /supplied request data/);
                        assert.throws(() => new XMLHttpRequest(), /supplied request data/);
                        assert.equal(deliveries, 0);
                      })().catch(error => { console.error(error); process.exitCode = 1; });")
        {:keys [exit err]} (shell/sh "node" "--eval" program)]
    (is (zero? exit) err)))
