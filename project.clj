(defproject tolgraven "0.1.0-SNAPSHOT"

  :description "FIXME: write description"
  :url "http://example.com/FIXME"

  :dependencies [[ch.qos.logback/logback-classic "1.2.3"]
                 [cheshire "5.10.0"]
                 [cljs-ajax "0.8.1"]
                 [clojure.java-time "0.3.2"]
                 [com.cognitect/transit-clj "1.0.324"]
                 [com.datomic/datomic-free "0.9.5697" :exclusions [org.slf4j/log4j-over-slf4j org.slf4j/slf4j-nop com.google.guava/guava]]
                 [com.fasterxml.jackson.core/jackson-core "2.11.2"]
                 [com.fasterxml.jackson.core/jackson-databind "2.11.2"]
                 [com.google.guava/guava "25.1-jre"]
                 [com.walmartlabs/lacinia "0.37.0"]
                 [cprop "0.1.17"]
                 [expound "0.8.5"]
                 [funcool/struct "1.4.0"]
                 [io.rkn/conformity "0.5.4"]

                 [luminus-transit "0.1.2"]
                 [luminus-undertow "0.1.7"]
                 [luminus/ring-ttl-session "0.3.3"]

                 [markdown-clj "1.10.5"]
                 [com.andrewmcveigh/cljs-time "0.5.2"]

                 [metosin/jsonista "0.2.7"]
                 [metosin/muuntaja "0.6.7"]
                 [metosin/reitit "0.5.5"]
                 [metosin/ring-http-response "0.9.1"]

                 [mount "0.1.16"]
                 [nrepl "0.8.2"]
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.773" :scope "provided"]
                 [org.clojure/data.json "1.0.0"]
                 [org.clojure/tools.cli "1.0.194"]
                 [org.clojure/tools.logging "1.1.0"]

                 [org.webjars.npm/bulma "0.9.0"]
                 [org.webjars.npm/material-icons "0.3.1"]
                 [org.webjars/webjars-locator "0.40"]
                 [org.webjars/webjars-locator-jboss-vfs "0.1.0"]

                 [cljsjs/smoothscroll-polyfill "0.4.0-0"]

                 [appliedscience/js-interop "0.1.10"]

                 [re-frame "1.1.1"]
                 [re-graph "0.1.14"]
                 [day8.re-frame/http-fx "0.2.1"]
                 [day8.re-frame/undo "0.3.3"]
                 [reagent "0.10.0"]
                 [re-frame-utils "0.1.0"]

                 [cljsjs/react-highlight "1.0.7-2"]
                 [cljsjs/highlight "9.12.0-2"]

                 [ring-webjars "0.2.0"]
                 [ring/ring-core "1.8.1"]
                 [ring/ring-defaults "0.3.2"]

                 [selmer "1.12.28"]]

  :min-lein-version "2.0.0"

  :source-paths ["src/clj" "src/cljs" "src/cljc"]
  :test-paths ["test/clj"]
  :resource-paths ["resources"]
  :target-path "target/%s/"
  :main ^:skip-aot tolgraven.core

  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-sassc "0.10.4"]
            [lein-auto "0.1.2"]
            [lein-kibit "0.1.2"]]
  :sassc
  [{:src "resources/scss/main.scss"
    :output-to "resources/public/css/main2.css"
    :style "nested"
    :import-path "resources/scss"}]
  :auto
  {"sassc" {:file-pattern #"\.(scss|sass)$" :paths ["resources/scss"]}}

  ; :hooks [leiningen.sassc]
  :clean-targets ^{:protect false}
  [:target-path [:cljsbuild :builds :app :compiler :output-dir]
   [:cljsbuild :builds :app :compiler :output-to]]
  :figwheel
  {:http-server-root "public"
  ; {:http-server-root "resources/public"
   :server-port 4001
   :ring-handler tolgraven.handler/app-routes ;Embed ring handler in figwheel http-kit server, for simple ring servers, if it doesn't work for you just run your own (see lein-ring)
   :server-logfile "log/figwheel-logfile.log"
   ; :ring-handler tolglow-web.handler/app-routes ;Embed ring handler in figwheel http-kit server, for simple ring servers, if it doesn't work for you just run your own (see lein-ring)
   :nrepl-port 7002
   :css-dirs ["resources/public/css"]
   :nrepl-middleware [cider.piggieback/wrap-cljs-repl]}

  ; :cljsbuild
  ; {:builds
  ;   {:app
  ;   {:source-paths ["src/cljs" "src/cljc" "env/dev/cljs"]
  ;     :figwheel {:on-jsload "tolgraven.core/mount-components"} ; only this bc dev/app calls init! (and more!) which also gets round multi-remount conundrum i solved uglily in cue-db... very reasonable to keep dev things away from general codebase so auto disabled
  ;     :compiler {:output-dir "resources/public/js/compiled/out"
  ;                :output-to "resources/public/js/compiled/app.js"
  ;                :asset-path "js/compiled/out"
  ;                ; :npm-deps {:three "0.108.0"
  ;                ;            :smoothscroll-polyfill "0.4.4"}
  ;                ; :install-deps true
  ;                :optimizations :advanced
  ;                :main "tolgraven.app" ;what is this why .app? ;; bc env/dev/app.cljs thingy. calls init
  ;     }}}}
  ; "Set up Figwheel as normal, but make sure :cljsbuild and :figwheel settings are in the root of your Leiningen project definition."
  ; ^ could this be issue?
  :profiles
  {:dev           [:project/dev :profiles/dev]
   :test          [:project/dev :project/test :profiles/test]

   :project/dev  {:jvm-opts ["-Dconf=dev-config.edn"]
                  :dependencies [[binaryage/devtools "1.0.2"]
                                 [cider/piggieback "0.5.1"]
                                 [doo "0.1.11"]
                                 [figwheel-sidecar "0.5.20"]
                                 ; [com.bhauman/figwheel-main "0.2.11"]
                                 ; [com.bhauman/rebel-readline-cljs "0.1.4"] ;; optional but recommended
                                 [pjstadig/humane-test-output "0.10.0"]
                                 [prone "2020-01-17"]
                                 [re-frisk "1.3.4"]
                                 [ring/ring-devel "1.8.1"]
                                 [ring/ring-mock "0.4.0"]]
                  :plugins      [[com.jakemccrary/lein-test-refresh "0.24.1"]
                                 [jonase/eastwood "0.3.5"]
                                 [lein-doo "0.1.11"]
                                 [lein-figwheel "0.5.20"]]
                  :cljsbuild
                  {:builds
                   {:app
                    {:source-paths ["src/cljs" "src/cljc" "env/dev/cljs"]
                     :figwheel {:on-jsload "tolgraven.core/mount-components"} ; only this bc dev/app calls init! (and more!) which also gets round multi-remount conundrum i solved uglily in cue-db... very reasonable to keep dev things away from general codebase so auto disabled
                     :compiler
                     {:output-dir "resources/public/js/compiled/out"
                      :output-to "resources/public/js/compiled/app.js"
                      :asset-path "js/compiled/out"
                      :closure-defines {"re_frame.trace.trace_enabled_QMARK_" true}
                      :optimizations :none
                      :parallel-build true
                      :preloads [re-frisk.preload devtools.preload]
                      :external-config
                      {:devtools/config
                       {:features-to-install [:formatters :hints] ;add exception hints
                        :cljs-land-style "background-color: rgb(30, 30, 30, 0.5); color: #edc; border-radius: 7px;"
                        :nil-style       "color: #d18479;"
                        :keyword-style   "color: #76a2ab;"
                        :integer-style   "color: #bd979d;"
                        :float-style     "color: #bd979d;"
                        :string-style    "color: #b4b88d;"
                        :symbol-style    "color: #edc;"
                        :bool-style      "color: #d18479;"
                        :print-config-overrides false}
                       #_(merge {:features-to-install [:formatters :hints] ;add exception hints
                                 :print-config-overrides false}
                                (let [colors {:nil "d18479" :keyword "76a2ab" :integer "bd979d" :float "bd979d"
                                              :string "b4b88d" :symbol "edc" :bool "d18479"}]
                                  (into {:cljs-land-style "background-color: rgb(30, 30, 30, 0.5); color: #edc; border-radius: 7px;"}
                                        (for [[k color] colors]
                                          [(keyword (str (name key) "-style"))
                                           (str "color: #" color ";")]))))

                          ; :nil-style "color: #d18479;" :keyword-style "color: #76a2ab;" :integer-style "color: #bd979d;"
                          ; :float-style "color: #bd979d;" :string-style "color: #b4b88d;" :symbol-style "color: #edc;" :bool-style "color: #d18479;"
                       }
                      :source-map true
                      :main "tolgraven.app" ;what is this why .app? ;; bc env/dev/app.cljs thingy. calls init
                      :pretty-print true}}}} ;} ;}


                  :doo {:build "test"}
                  :source-paths ["env/dev/clj"]
                  :resource-paths ["env/dev/resources"]
                  :repl-options {:welcome #_"in DEV profile" (println "in DEV profile")
                                 :init-ns user
                                 :nrepl-middleware [cider.piggieback/wrap-cljs-repl] ; then just launch fucker w eg :IcedStartCljsRepl figwheel-sidecar
                                 :timeout 300000}
                  :injections [(require 'pjstadig.humane-test-output)
                               (pjstadig.humane-test-output/activate!)]}
   :project/test {:jvm-opts ["-Dconf=test-config.edn"]
                  :resource-paths ["env/test/resources"]
                  :cljsbuild
                  {:builds
                   {:test
                    {:source-paths ["src/cljc" "src/cljs" "test/cljs"]
                     :compiler
                     {:output-to "target/test.js"
                      :main "tolgraven.doo-runner"
                      :optimizations :whitespace
                      :pretty-print true}}}}}

   :uberjar {:omit-source true
             :prep-tasks ["compile" ["cljsbuild" "once" "min"]]
             :cljsbuild {:builds
                         {:min
                          {:source-paths ["src/cljc" "src/cljs" "env/prod/cljs"]
                           :compiler
                           {:output-dir "resources/public/js/compiled"
                            :output-to "resources/public/js/compiled/app.js"
                            :source-map "resources/public/js/compiled/app.js.map"
                            :asset-path "js/compiled/out"
                            :optimizations :advanced
                            :pretty-print false
                            :infer-externs true
                            :parallel-build true
                            :main "tolgraven.app"
                            :closure-warnings
                            {:externs-validation :off :non-standard-jsdoc :off}
                            :externs ["react/externs/react.js"]}}}}

             :aot :all
             :uberjar-name "tolgraven.jar"
             :source-paths ["env/prod/clj"]
             :resource-paths ["env/prod/resources"]}

   :profiles/dev {}
   :profiles/test {}})
