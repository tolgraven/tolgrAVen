(defproject tolgraven "0.1.0-SNAPSHOT"

  :description "FIXME: write description"
  :url "http://example.com/FIXME"

  :dependencies [[clojure.java-time "0.3.2"]
                 [com.cognitect/transit-clj "1.0.324"]
                 
                 [org.clojure/java.jdbc "0.7.11"]
                 [org.postgresql/postgresql "42.2.18"]
                 [honeysql "1.0.444"]
                 [com.google.guava/guava "25.1-jre"] ;what is
                 [cprop "0.1.17"] ;env loading
                 [expound "0.8.6"] ;spec failure explainers
                 [funcool/struct "1.4.0"] ;is what?

                 [luminus-transit "0.1.2"]
                 [luminus-undertow "0.1.7"]
                 [luminus/ring-ttl-session "0.3.3"]

                 [markdown-clj "1.10.5"]

                 [metosin/jsonista "0.2.7"] ;also json en/decoding. supposedly faster. [cheshire "5.10.0"] ;json decoding
                 [metosin/muuntaja "0.6.7"]
                 [metosin/reitit "0.5.10" :exclusions [org.clojure/spec.alpha]]
                 [metosin/ring-http-response "0.9.1"]

                 [mount "0.1.16"]
                 [nrepl "0.8.2"]
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.773" :scope "provided" :exclusions [com.google.errorprone/error_prone_annotations]]
                 [org.clojure/tools.cli "1.0.194"]
                 [org.clojure/core.async "1.3.610"]

                 ; LOGGING
                 [org.clojure/tools.logging "1.1.0"]
                 [com.taoensso/timbre "5.1.0" :exclusions [org.clojure/tools.reader]]
                 [com.fzakaria/slf4j-timbre "0.3.20" :exclusions [org.slf4j/slf4j-api]] ;route java logging through timbre. but dunno. hard to manage
                 [clj-logging-config "1.9.12"]
                 [radicalzephyr/ring.middleware.logger "0.6.0"]
                 [io.aviso/pretty "0.1.37"] ;pretty exceptions, pretty logging...

                 [ring/ring-core "1.8.2"]
                 [ring/ring-defaults "0.3.2"]
                 [bk/ring-gzip "0.3.0" :exclusions [clojure-complete]]

                 [selmer "1.12.31"]

                 ; CLJS
                 [cljs-ajax "0.8.1"  :exclusions [com.cognitect/transit-cljs com.cognitect/transit-cljs]]
                 [com.andrewmcveigh/cljs-time "0.5.2"]
                 [cljsjs/smoothscroll-polyfill "0.4.0-0"]

                 [appliedscience/js-interop "0.2.5"]

                 [re-frame "1.1.1"]
                 [day8.re-frame/http-fx "0.2.1"]
                 [akiroz.re-frame/storage "0.1.4"] ;localstorage.
                 ; [com.smxemail/re-frame-document-fx "0.0.1-SNAPSHOT"] ;https://github.com/SMX-LTD/re-frame-document-fx
                 [com.degel/re-frame-firebase "0.8.0" :exclusions [args4j]]
                 ; [day8.re-frame/undo "0.3.3"]
                 [reagent "0.10.0"]
                 ; [re-frame-utils "0.1.0"]
                 
                 [cljsjs/highlight "9.12.0-2"]
                 [cljsjs/codemirror "5.44.0-1"]
                 [cljsjs/react-transition-group "4.3.0-0"]]

  ; :managed-dependencies [] ; can pin specific versions...

  :min-lein-version "2.0.0"

  :source-paths ["src/clj" "src/cljs" "src/cljc"]
  :test-paths ["test/clj"]
  :resource-paths ["resources"]
  :target-path "target/%s/"
  :main ^:skip-aot tolgraven.core

  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-sassc "0.10.4"]
            [lein-autoprefixer "0.1.1"]
            [lein-auto "0.1.2"]
            ; [lein-npm "0.6.2"]
            ; [lein-kibit "0.1.2"]
            ]
  :sassc
  [{:src "resources/scss/main.scss"
    :output-to "resources/public/css/main.css"
    :style "nested"
    :import-path "resources/scss"}]
  :autoprefixer {:src "resources/public/css"
                 :browsers "> 1%, Last 2 versions"} ;; optional
  :auto
  {"sassc" {:file-pattern #"\.(scss|sass)$" :paths ["resources/scss"]}
   "autoprefixer" {:file-pattern #"\.(css)$" :paths ["resources/public/css"]}}
  
  :aliases {"cssbuild" ["do" ["sass" "once"] "autoprefixer"]}
  ; :npm {:dependencies
  ;       [[]]}
  
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
                                 [figwheel-sidecar "0.5.20" :exclusions [args4j]]
                                 ; [com.bhauman/figwheel-main "0.2.11"]
                                 ; [com.bhauman/rebel-readline-cljs "0.1.4"] ;; optional but recommended
                                 [alembic "0.3.2"]
                                 [pjstadig/humane-test-output "0.10.0"]
                                 [prone "2020-01-17"]
                                 ; [re-frisk-remote "1.3.4"  :exclusions [org.clojure/core.async org.clojure/tools.analyzer.jvm org.clojure/tools.analyzer org.clojure/core.memoize org.clojure/core.cache]]
                                 ; [day8.re-frame/re-frame-10x "0.7.0"]
                                 ; [day8.re-frame/tracing "0.6.0"]
                                 [ring/ring-devel "1.8.2"]
                                 [ring/ring-mock "0.4.0"]]
                  :plugins      [[com.jakemccrary/lein-test-refresh "0.24.1"]
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
                      ; :closure-defines {"re_frame.trace.trace_enabled_QMARK_" true}
                      :optimizations :none
                      :parallel-build true
                      ; :preloads [re-frisk-remote.preload devtools.preload day8.re-frame-10x.preload]
                      ; :preloads [devtools.preload day8.re-frame-10x.preload] ;can remove devtools preload cause have in app.cljs?
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
                        :print-config-overrides false} }
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
