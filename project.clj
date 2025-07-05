(defproject tolgraven "0.1.0-SNAPSHOT"
  :description "tolgrAVen.se website"
  :url "https://tolgraven.se"

  :dependencies [[thheller/shadow-cljs "3.1.7"]

                 ; all of shadows deps, explicit
                 [org.clojure/clojure "1.12.1"]
                 [org.clojure/data.json "2.5.1"]
                 [org.clojure/tools.cli "1.1.230"]
                 [org.clojure/tools.reader "1.5.2"]
                 ; [nrepl "1.3.1"]
                 [com.cognitect/transit-clj "1.0.333"]
                 [com.cognitect/transit-cljs "0.8.280"]
                 [org.clojure/core.async "1.8.741"]
                 [org.clojure/clojurescript "1.12.42"]
                 [com.google.javascript/closure-compiler-unshaded "v20250528"]
                 [org.clojure/google-closure-library "0.0-20250515-87401eb8"]
                 [org.clojure/google-closure-library-third-party "0.0-20250515-87401eb8"]
                 [thheller/shadow-util "0.7.0"]
                 [thheller/shadow-client "1.4.0"]
                 [thheller/shadow-undertow "0.3.4"]
                 [thheller/shadow-cljsjs "0.0.22"]
                 [hiccup "2.0.0"]
                 [ring/ring-core "1.14.2"]
                 [io.methvin/directory-watcher "0.19.1"]
                 [expound "0.9.0"]
                 [fipp "0.6.27"]
                 [com.bhauman/cljs-test-display "0.1.1"]

                 [clojure.java-time "1.4.3"]
                 ; [com.cognitect/transit-clj "1.0.333"]
                 
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.postgresql/postgresql "42.7.7"]
                 [honeysql "1.0.461"]
                 ; [com.google.guava/guava "25.1-jre"] ;what is
                 [cprop "0.1.20"] ;env loading
                 ; [expound "0.9.0"] ;spec failure explainers
                 [funcool/struct "1.4.0"] ;is what?
                 [clj-http "3.13.1"]

                 [luminus-transit "0.1.6"]
                 [luminus-undertow "0.1.18"]
                 [luminus/ring-ttl-session "0.3.3"]

                 [markdown-clj "1.12.4"]

                 [metosin/jsonista "0.3.13"] ;also json en/decoding. supposedly faster. [cheshire "5.10.0"] ;json decoding
                 [metosin/muuntaja "0.6.11"]
                 [metosin/reitit "0.9.1" :exclusions [org.clojure/spec.alpha com.fasterxml.jackson.core/jackson-core com.fasterxml.jackson.core/jackson-databind com.fasterxml.jackson.core/jackson-annotations]]

                 [mount "0.1.23"]

                 ; LOGGING
                 [org.clojure/tools.logging "1.3.0"]
                 [com.taoensso/timbre "6.7.1" :exclusions [org.clojure/tools.reader]]
                 [com.taoensso/encore "3.149.0"]
                 [com.fzakaria/slf4j-timbre "0.4.1" :exclusions [org.slf4j/slf4j-api]] ;route java logging through timbre. but dunno. hard to manage
                 [clj-logging-config "1.9.12"]
                 [io.aviso/pretty "1.4.4"] ;pretty exceptions, pretty logging...

                 ; [ring/ring-core "1.10.0" :exclusions [commons-io]]
                 [ring/ring-defaults "0.6.0" :exclusions [commons-io]]
                 ; [bk/ring-gzip "0.3.0" :exclusions [clojure-complete]]
                 [amalloy/ring-gzip-middleware "0.1.4"]
                 [ring-partial-content "2.1.0"] ; handle safari video playback / 206 response
                 [ring-basic-authentication "1.2.0"]
                 [radicalzephyr/ring.middleware.logger "0.6.0"]
                 [metosin/ring-http-response "0.9.5"]
                 [toyokumo/ring-middleware-csp "0.4.63"]
                 [ring-ratelimit "0.2.3"] ; probably useful if I end up working on some popular website. so probably not anytime soon lol
                 [clojure-interop/apache-commons-io "1.0.0"]
                 
                 [optimus "2025.01.19.2"] ;optimization of assets
                 [optimus-img-transform "0.3.1"]
                 [optimus-sass "0.0.3"] ; load sass straight. pretty old dunno if still works
                 [optimus-autoprefixer "0.1.0"] ; same but autoprefixer. might be nice can avoid npm build and whatnot plus keep everything in one spot...

                 [sitemap "0.4.0"]
                 ; [nrepl/drawbridge "0.2.1"] ; nrepl over http

                 ; CLJS
                 [cljs-ajax "0.8.4"  :exclusions [com.cognitect/transit-cljs com.cognitect/transit-cljs]]
                 [com.andrewmcveigh/cljs-time "0.5.2"]

                 [appliedscience/js-interop "0.2.5"]
                 
                 [differ "0.3.3"] ; diff with patch to apply

                 [re-frame "1.4.3"]
                 ; [kee-frame "0.4.0" :exclusions [args4j]]
                 [day8.re-frame/http-fx "0.2.4"]
                 [superstructor/re-frame-fetch-fx "0.4.0"]
                 [com.yetanalytics/sse-fx "0.1.1"]
                 [superstructor/re-frame-clipboard-fx "0.0.2"]
                 [re-frame-datatable "0.6.0"]
                 [breaking-point "0.1.2"]
                 
                 [akiroz.re-frame/storage "0.1.4"] ;localstorage.
                 [day8.re-frame/async-flow-fx "0.4.0"]
                 ; [com.smxemail/re-frame-document-fx "0.0.1-SNAPSHOT"] ;https://github.com/SMX-LTD/re-frame-document-fx
                 ; [com.degel/re-frame-firebase "0.10.0-SNAPSHOT" :exclusions [args4j]]
                 [com.degel/re-frame-firebase "0.8.0" :exclusions [args4j cljsjs/firebase]]
                 ; [cljsjs/firebase "9.6.8-0"]
                 [reagent "1.3.0"]
                 [cljsjs/react "18.3.1-1"]
                 [cljsjs/react-dom "18.3.1-1"]
                 [reanimated "0.6.1"]
                 [re-frame-utils "0.1.0"]
                 [com.smxemail/re-frame-cookie-fx "0.0.2"  :exclusions [args4j]]
                 [re-pollsive "0.1.0"] ; periodic polling/timer stuff

                 ; INVESTIGATE
                 ; [com.github.oliyh/martian-re-frame "0.1.24"] ; interact nicely with http endpoints off swagger/openapi
                 
                 [superstructor/re-highlight "2.0.2"]
                 [cljsjs/highlight "11.7.0-0"]
                 [cljsjs/codemirror "5.65.16-0"]
                 [cljsjs/react-player "1.11.0-0"]
                 [cljsjs/react-leaflet "3.1.0-0"]
                 [cljsjs/leaflet "1.9.4-0"]
                 [cljsjs/react-transition-group "4.3.0-0"]]

  ; :managed-dependencies [] ; can pin specific versions...

  :min-lein-version "2.0.0"

  :source-paths ["src/clj" "src/cljs" "src/cljc"]
  :test-paths ["test/clj"]
  :resource-paths ["resources"]
  :target-path "target/%s/"
  :main ^:skip-aot tolgraven.core

  :plugins [[lein-cljsbuild "1.1.8"]
            [lein-sassc "0.10.4"]
            [lein-autoprefixer "0.1.1"]
            [lein-auto "0.1.2"]
            [lein-codox "0.10.8"]
            ; [venantius/ultra "0.5.4"]
            ; [lein-npm "0.6.2"]; "no more lein-npm, even few packages gives hundreds of deps total and slows down figwheel reloads something fierce"
            ; [lein-kibit "0.1.2"]
            ]
  :sassc
  [{:src "resources/scss/main.scss"
    :output-to "resources/public/css/tolgraven/main.css"
    :style "nested"
    :import-path "resources/scss"}]
  :autoprefixer {:src "resources/public/css/tolgraven"
                 :browsers "> 5%, Last 2 versions"
                 :output-to "resources/public/css/tolgraven/main.min.css"} ;; optional
  :auto
  {"sassc" {:file-pattern #"\.(scss|sass)$" :paths ["resources/scss"]}
   "autoprefixer" {:file-pattern #"\.(css)$" :paths ["resources/public/css/tolgraven"]}}
  
  :aliases {"cssbuild" ["do" ["sassc" "once"] "autoprefixer"]}
  ; :aliases {"fig" ["trampoline" "run" "-m" "figwheel.main"]
  ;           "build-dev" ["trampoline" "run" "-m" "figwheel.main" "-b" "dev" "-r"]}
  :clean-targets ^{:protect false}
  [:target-path
   :compile-path
   [:cljsbuild :builds :app :compiler :output-dir]
   [:cljsbuild :builds :app :compiler :output-to]
   "resources/public/js/compiled/out"
   "resources/public/js/compiled/app.js"]

  :codox
  {:language :clojurescript
   :output-path "resources/docs/codox"
   :metadata {:doc/format :markdown}
   :source-uri "https://github.com/tolgraven/tolgraven/blob/master/{filepath}#L{line}"}

  :profiles
  {:dev           [:project/dev :profiles/dev]
   :test          [:project/dev :project/test :profiles/test]
   :stage         [:uberjar :profiles/stage]

   :project/dev  {:jvm-opts ["-Dconf=dev-config.edn" "-XX:-OmitStackTraceInFastThrow"]
                  :dependencies [[binaryage/devtools "1.0.7"]
                                 [doo "0.1.11"]
                                 ; [com.bhauman/rebel-readline-cljs "0.1.4"] ;; optional but recommended
                                 ; [alembic "0.3.2"]
                                 [pjstadig/humane-test-output "0.11.0"]
                                 [prone "2021-04-23"]
                                 [re-frisk "1.7.1"]
                                 ; [re-frisk-remote "1.5.2"]
                                 [day8.re-frame/re-frame-10x "1.10.0" :exclusions [superstructor/re-highlight]]
                                 [djblue/portal "0.59.1"]
                                 ; [day8.re-frame/tracing "0.6.2"]
                                 [ring/ring-devel "1.14.2"]
                                 [ring/ring-mock "0.6.1"]]
                  :plugins      [;[refactor-nrepl/refactor-nrepl "3.9.0"]
                                 [cider/cider-nrepl "0.57.0"]
                                 #_[com.jakemccrary/lein-test-refresh "0.24.1"]
                                 #_[lein-doo "0.1.11"]
                                 #_[lein-figwheel "0.5.20"]]
                  :cljsbuild
                  {:builds
                   {:app
                    
                    {:source-paths ["src/cljs" "src/cljc" "env/dev/cljs"]
                     #_:figwheel #_{:on-jsload "tolgraven.core/mount-components"} ; only this bc dev/app calls init! (and more!) which also gets round multi-remount conundrum i solved uglily in cue-db... very reasonable to keep dev things away from general codebase so auto disabled
                     :compiler
                     {:output-dir "resources/public/js/compiled/out"
                      :output-to "resources/public/js/compiled/app.js"
                      :asset-path "js/compiled/out"
                      ; :init-fn tolgraven.core/init!
                      
                      ; :modules { 
                      ;           :app {:entries #{tolgraven.core tolgraven.app}
                      ;                  :output-to "resources/public/js/compiled/app.js"} }
                      
                      :language-in     :ecmascript-next
                      :language-out    :ecmascript-next
                      :optimizations :none
                      :infer-externs true
                      :parallel-build true ;or java only uses 100% cpu, gross
                      :recompile-dependents false
                      :preloads [devtools.preload ;can remove devtools preload cause have in app.cljs?
                                 re-frisk.preload-hidden
                                 ; day8.re-frame-10x.preload
                                 #_re-frisk.preload #_re-frisk-remote.preload]
                      :closure-defines {goog.DEBUG true
                                        "re_frame.trace.trace_enabled_QMARK_" true}
                      :external-config
                      {:devtools/config
                       {;:features-to-install [:formatters :hints] ;add exception hints
                        :cljs-land-style "background-color: rgb(30, 30, 30, 0.5); color: #edc; border-radius: 7px;"
                        :nil-style       "background-color: #282828; color: #d18479;"
                        :keyword-style   "background-color: #282828; color: #76a2ab;"
                        :integer-style   "background-color: #282828; color: #bd979d;"
                        :float-style     "background-color: #282828; color: #bd979d;"
                        :string-style    "background-color: #282828; color: #b4b88d;"
                        :symbol-style    "background-color: #282828; color: #edc;"
                        :bool-style      "background-color: #282828; color: #d18479;"
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
                      :source-map-timestamp true
                      :main "tolgraven.app" ;what is this why .app? ;; bc env/dev/app.cljs thingy. calls init
                      :pretty-print true}}}} ;} ;}

                  :doo {:build "test"}
                  :source-paths ["env/dev/clj"]
                  :resource-paths ["env/dev/resources"]
                  :repl-options {:welcome (println "in DEV profile")
                                 :init-ns user
                                 :port 7000
                                 :init (start)
                                 :nrepl-middleware [shadow.cljs.devtools.server.nrepl/middleware
                                                    ; cider.nrepl/cider-middleware
                                                    ; refactor-nrepl.middleware/wrap-refactor
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

   :uberjar {;:omit-source true
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
                            :source-map-timestamp true
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
   :profiles/test {}
   :profiles/stage {}})
