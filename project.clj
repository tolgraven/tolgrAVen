(defproject tolgraven "0.1.0-SNAPSHOT"

  :description "FIXME: write description"
  :url "http://example.com/FIXME"

  :dependencies [[clojure.java-time "0.3.3"]
                 [com.cognitect/transit-clj "1.0.329"]
                 
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.postgresql/postgresql "42.3.3"]
                 [honeysql "1.0.461"]
                 [com.google.guava/guava "25.1-jre"] ;what is
                 [cprop "0.1.19"] ;env loading
                 [expound "0.9.0"] ;spec failure explainers
                 [funcool/struct "1.4.0"] ;is what?

                 [luminus-transit "0.1.5"]
                 [luminus-undertow "0.1.14"]
                 [luminus/ring-ttl-session "0.3.3"]

                 [markdown-clj "1.11.0"]

                 [metosin/jsonista "0.3.5"] ;also json en/decoding. supposedly faster. [cheshire "5.10.0"] ;json decoding
                 [metosin/muuntaja "0.6.8"]
                 [metosin/reitit "0.5.18" :exclusions [org.clojure/spec.alpha com.fasterxml.jackson.core/jackson-core com.fasterxml.jackson.core/jackson-databind com.fasterxml.jackson.core/jackson-annotations]]

                 [mount "0.1.16"]
                 [nrepl "0.9.0"]
                 [org.clojure/clojure "1.11.1"]
                 [org.clojure/clojurescript "1.11.4" :scope "provided" :exclusions [com.google.errorprone/error_prone_annotations]]
                 [org.clojure/tools.cli "1.0.206"]
                 [org.clojure/core.async "1.5.648"]

                 ; LOGGING
                 [org.clojure/tools.logging "1.2.4"]
                 [com.taoensso/timbre "5.2.1" :exclusions [org.clojure/tools.reader]]
                 [com.fzakaria/slf4j-timbre "0.3.21" :exclusions [org.slf4j/slf4j-api]] ;route java logging through timbre. but dunno. hard to manage
                 [clj-logging-config "1.9.12"]
                 [io.aviso/pretty "1.1.1"] ;pretty exceptions, pretty logging...

                 [ring/ring-core "1.9.5"]
                 [ring/ring-defaults "0.3.3"]
                 [bk/ring-gzip "0.3.0" :exclusions [clojure-complete]]
                 [ring-partial-content "2.0.1"] ; handle safari video playback / 206 response
                 [ring-basic-authentication "1.1.1"]
                 [radicalzephyr/ring.middleware.logger "0.6.0"]
                 [metosin/ring-http-response "0.9.3"]
                 [optimus "2022-02-13"] ;optimization of assets
                 [optimus-img-transform "0.3.1"]
                 ; [optimus-sass "0.0.3"] ; load sass straight. pretty old dunno if still works
                 ; [optimus-autoprefixer "0.1.0"] ; same but autoprefixer. might be nice can avoid npm build and whatnot plus keep everything in one spot...

                 ; [nrepl/drawbridge "0.2.1"] ; nrepl over http

                 ; CLJS
                 [cljs-ajax "0.8.4"  :exclusions [com.cognitect/transit-cljs com.cognitect/transit-cljs]]
                 [com.andrewmcveigh/cljs-time "0.5.2"]
                 [cljsjs/smoothscroll-polyfill "0.4.0-0"]

                 [appliedscience/js-interop "0.2.5"]

                 [re-frame "1.2.0"]
                 ; [kee-frame "0.4.0" :exclusions [args4j]]
                 [day8.re-frame/http-fx "0.2.4"]
                 [akiroz.re-frame/storage "0.1.4"] ;localstorage.
                 [day8.re-frame/async-flow-fx "0.3.0"]
                 ; [com.smxemail/re-frame-document-fx "0.0.1-SNAPSHOT"] ;https://github.com/SMX-LTD/re-frame-document-fx
                 [com.degel/re-frame-firebase "0.8.0" :exclusions [args4j]]
                 [reagent "1.1.1"]
                 [cljsjs/react "17.0.2-0"]
                 [cljsjs/react-dom "17.0.2-0"]
                 [reanimated "0.6.1"]
                 [re-frame-utils "0.1.0"]
                 [com.smxemail/re-frame-cookie-fx "0.0.2"  :exclusions [args4j]]
                 [re-pollsive "0.1.0"] ; periodic polling/timer stuff
                 
                 [cljsjs/highlight "10.3.1-0"]
                 [cljsjs/codemirror "5.44.0-1"]
                 [cljsjs/react-player "1.11.0-0"]
                 [cljsjs/react-leaflet "3.1.0-0"]
                 [cljsjs/leaflet "1.7.1-0"]
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
            [lein-codox "0.10.8"]
            ; [lein-npm "0.6.2"]; "no more lein-npm, even few packages gives hundreds of deps total and slows down figwheel reloads something fierce"
            ; [lein-kibit "0.1.2"]
            ]
  :sassc
  [{:src "resources/scss/main.scss"
    :output-to "resources/public/css/tolgraven/main.css"
    :style "nested"
    :import-path "resources/scss"}]
  :autoprefixer {:src "resources/public/css/tolgraven"
                 :browsers "> 5%, Last 2 versions"} ;; optional
  :auto
  {"sassc" {:file-pattern #"\.(scss|sass)$" :paths ["resources/scss"]}
   "autoprefixer" {:file-pattern #"\.(css)$" :paths ["resources/public/css/tolgraven"]}}
  
  :aliases {"cssbuild" ["do" ["sassc" "once"] "autoprefixer"]}
  :clean-targets ^{:protect false}
  [:target-path
   :compile-path
   [:cljsbuild :builds :app :compiler :output-dir]
   [:cljsbuild :builds :app :compiler :output-to]
   "resources/public/js/compiled/out"
   "resources/public/js/compiled/app.js"]
  :figwheel
  {:http-server-root "public"
   :server-port 4001
   :ring-handler tolgraven.handler/app-routes ;Embed ring handler in figwheel http-kit server, for simple ring servers, if it doesn't work for you just run your own (see lein-ring)
   :server-logfile "log/figwheel-logfile.log"
   :nrepl-port 7002
   :css-dirs ["resources/public/css"]
   :nrepl-middleware [cider.piggieback/wrap-cljs-repl]}

  :codox
  {:language :clojurescript
   :output-path "resources/docs/codox"
   :metadata {:doc/format :markdown}
   :source-uri "https://github.com/tolgraven/tolgraven/blob/master/{filepath}#L{line}"}

  :profiles
  {:dev           [:project/dev :profiles/dev]
   :test          [:project/dev :project/test :profiles/test]

   :project/dev  {:jvm-opts ["-Dconf=dev-config.edn"]
                  :dependencies [[binaryage/devtools "1.0.5"]
                                 ; [binaryage/dirac "1.6.1"]
                                 [cider/piggieback "0.5.3"]
                                 [doo "0.1.11"]
                                 [figwheel-sidecar "0.5.20" :exclusions [args4j]]
                                 ; [com.bhauman/figwheel-main "0.2.11"]
                                 ; [com.bhauman/rebel-readline-cljs "0.1.4"] ;; optional but recommended
                                 ; [alembic "0.3.2"]
                                 [pjstadig/humane-test-output "0.11.0"]
                                 [prone "2021-04-23"]
                                 ; [re-frisk "1.3.4"  :exclusions [org.clojure/core.async org.clojure/tools.analyzer.jvm org.clojure/tools.analyzer org.clojure/core.memoize org.clojure/core.cache]]
                                 [re-frisk "1.5.2"]
                                 ; [re-frisk-remote "1.5.2"]
                                 ; [day8.re-frame/re-frame-10x "0.7.0"]
                                 ; [day8.re-frame/tracing "0.6.0"]
                                 [ring/ring-devel "1.9.5"]
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
                      :optimizations :none
                      :parallel-build true ;or java only uses 100% cpu, gross
                      :recompile-dependents false
                      ; :preloads [re-frisk-remote.preload devtools.preload day8.re-frame-10x.preload]
                      :preloads [re-frisk.preload]
                      ; :preloads [re-frisk-remote.preload]
                      ; :preloads [devtools.preload day8.re-frame-10x.preload] ;can remove devtools preload cause have in app.cljs?
                      :closure-defines {goog.DEBUG true "re_frame.trace.trace_enabled_QMARK_" true}
                      :external-config
                      {:devtools/config
                       {;:features-to-install [:formatters :hints] ;add exception hints
                        :cljs-land-style "background-color: rgb(30, 30, 30, 0.5); color: #edc; border-radius: 7px;"
                        :nil-style       "color: #d18479;"
                        :keyword-style   "color: #76a2ab;"
                        :integer-style   "color: #bd979d;"
                        :float-style     "color: #bd979d;"
                        :string-style    "color: #b4b88d;"
                        :symbol-style    "color: #edc;"
                        :bool-style      "color: #d18479;"
                        :print-config-overrides false}
                       :dirac.runtime/config
                       { ; foreground colors
                        :rich-text-ansi-style-30 "color: #edc"                                       ; black
                        :rich-text-ansi-style-31 "color: #d18479"                                    ; red
                        :rich-text-ansi-style-32 "color: #b4b88d"                                    ; green
                        :rich-text-ansi-style-33 "color: rgb(128, 128, 0)"                           ; yellow
                        :rich-text-ansi-style-34 "color: #76a2ab"                                    ; blue
                        :rich-text-ansi-style-35 "color: #bd979d"                                    ; magenta
                        :rich-text-ansi-style-36 "color: rgb(0, 128, 128)"                           ; cyan
                        :rich-text-ansi-style-37 "color: rgb(128, 128, 128)"                         ; gray
                        ; background colors
                        :rich-text-ansi-style-40 "background-color: #282828"                         ; black
                        :rich-text-ansi-style-41 "background-color: rgba(128, 0, 0, 0.2)"            ; red
                        :rich-text-ansi-style-42 "background-color: rgba(0, 128, 0, 0.2)"            ; green
                        :rich-text-ansi-style-43 "background-color: rgba(128, 128, 0, 0.2)"          ; yellow
                        :rich-text-ansi-style-44 "background-color: rgba(0, 0, 128, 0.2)"            ; blue
                        :rich-text-ansi-style-45 "background-color: rgba(128, 0, 128, 0.2)"          ; magenta
                        :rich-text-ansi-style-46 "background-color: rgba(0, 128, 128, 0.2)"          ; cyan
                        :rich-text-ansi-style-47 "background-color: rgba(128, 128, 128, 0.2)"}}
                      :source-map true
                      :main "tolgraven.app" ;what is this why .app? ;; bc env/dev/app.cljs thingy. calls init
                      :pretty-print true}}}} ;} ;}


                  :doo {:build "test"}
                  :source-paths ["env/dev/clj"]
                  :resource-paths ["env/dev/resources"]
                  :repl-options {:welcome (println "in DEV profile")
                                 :init-ns user
                                 :port 7000
                                 :init (start)
                                 ; :init (do
                                 ;        (require 'dirac.agent)
                                 ;        (dirac.agent/boot!))
                                 :nrepl-middleware [cider.piggieback/wrap-cljs-repl  ; direc.nrepl/middleware
                                                    ] ; then just launch fucker w eg :IcedStartCljsRepl figwheel-sidecar
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
             :prep-tasks ["compile"
                          ["cljsbuild" "once" "min"]
                          ["codox"]]
             :cljsbuild {:builds
                         {:min
                          {:source-paths ["src/cljc" "src/cljs" "env/prod/cljs"]
                           :compiler
                           {:output-dir "resources/public/js/compiled"
                            :output-to "resources/public/js/compiled/app.js"
                            :source-map "resources/public/js/compiled/app.js.map"
                            :asset-path "js/compiled/out"
                            :closure-defines {goog.DEBUG false}
                            :optimizations :advanced
                            :language-in     :ecmascript-next
                            :language-out    :ecmascript-next
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
