(defproject tolgraven "0.1.0-SNAPSHOT"
  :description "tolgrAVen.se website"
  :url "https://tolgraven.se"

  :dependencies [[thheller/shadow-cljs "3.2.1"]
                 [thheller/shadow-util "0.7.0"]
                 [thheller/shadow-client "1.4.0"]
                 [thheller/shadow-undertow "0.3.4"]
                 [thheller/shadow-cljsjs "0.0.22"]

                 ; all of shadows deps, explicit
                 [org.clojure/clojure "1.12.2"]
                 [org.clojure/data.json "2.5.1"]
                 [org.clojure/tools.cli "1.1.230"]
                 [org.clojure/tools.reader "1.5.2"]
                 [nrepl "1.3.1"]
                 [com.cognitect/transit-clj "1.0.333"]
                 [com.cognitect/transit-cljs "0.8.280"]
                 [org.clojure/core.async "1.8.741"]
                 [org.clojure/clojurescript "1.12.42"]
                 [com.google.javascript/closure-compiler-unshaded "v20250820"]
                 [org.clojure/google-closure-library "0.0-20250515-87401eb8"]
                 [org.clojure/google-closure-library-third-party "0.0-20250515-87401eb8"]
                 [hiccup "2.0.0"]
                 ; [io.methvin/directory-watcher "0.19.1"]
                 [expound "0.9.0"]
                 [fipp "0.6.29"]
                 [com.bhauman/cljs-test-display "0.1.1"]

                 [clojure.java-time "1.4.3"]
                 ; [com.cognitect/transit-clj "1.0.333"]
                 
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.postgresql/postgresql "42.7.8"]
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
                 [com.taoensso/timbre "6.8.0" :exclusions [org.clojure/tools.reader]]
                 [com.taoensso/encore "3.154.0"]
                 [com.fzakaria/slf4j-timbre "0.4.1" :exclusions [org.slf4j/slf4j-api]] ;route java logging through timbre. but dunno. hard to manage
                 [clj-logging-config "1.9.12"]
                 [io.aviso/pretty "1.4.4"] ;pretty exceptions, pretty logging...

                 [ring/ring-core "1.15.2"]
                 [ring/ring-defaults "0.7.0" :exclusions [commons-io]]
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
                 ; [optimus-sass "0.0.3"] ; load sass straight. pretty old dunno if still works
                 ; [optimus-autoprefixer "0.1.0"] ; same but autoprefixer. might be nice can avoid npm build and whatnot plus keep everything in one spot...

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
                 [com.degel/re-frame-firebase "0.10.0-SNAPSHOT" :exclusions [args4j]]
                 ; [com.degel/re-frame-firebase "0.8.0" :exclusions [args4j cljsjs/firebase]]
                 [reagent "1.3.0"]
                 [cljsjs/react "18.3.1-1"]
                 [cljsjs/react-dom "18.3.1-1"]
                 [reanimated "0.6.1"]
                 [com.smxemail/re-frame-cookie-fx "0.0.2"  :exclusions [args4j]]
                 [re-pollsive "0.1.0"] ; periodic polling/timer stuff

                 ; INVESTIGATE
                 ; [com.github.oliyh/martian-re-frame "0.1.24"] ; interact nicely with http endpoints off swagger/openapi
                 
                 [cljsjs/codemirror "5.65.16-0"]
                 [cljsjs/react-player "1.11.0-0"]]

  ; :managed-dependencies [] ; can pin specific versions...

  :min-lein-version "2.0.0"

  :source-paths ["src/clj" "src/cljs" "src/cljc"]
  :test-paths ["test/clj"]
  :resource-paths ["resources"]
  :target-path "target/%s/"
  :main ^:skip-aot tolgraven.core

  :repositories
  [["private" {:url "https://tolgraven.hel1.your-objectstorage.com/m2/releases/"
               :no-auth true}]] ; uses injected credentials

  :plugins [[s3-wagon-private "1.3.5"]
            [lein-codox "0.10.8"]]

  :codox
  {:language :clojurescript
   :output-path "resources/docs/codox"
   :metadata {:doc/format :markdown}
   :namespaces [#"^(?!.*code\.cljs$).*\.cljs$"]
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
                                 [day8.re-frame/re-frame-10x "1.10.1" :exclusions [superstructor/re-highlight]]
                                 [djblue/portal "0.61.0"]
                                 ; [day8.re-frame/tracing "0.6.2"]
                                 [ring/ring-devel "1.15.2"]
                                 [ring/ring-mock "0.6.2"]]
                  :plugins      [;[refactor-nrepl/refactor-nrepl "3.9.0"]
                                 [cider/cider-nrepl "0.57.0"] ]

                  :doo {:build "test"}
                  :source-paths ["env/dev/clj"]
                  :resource-paths ["env/dev/resources"]
                  :repl-options {:welcome (println "in DEV profile")
                                 :init-ns user
                                 :port 7000
                                 :init (start)
                                 :nrepl-middleware [shadow.cljs.devtools.server.nrepl/middleware]
                                 :timeout 30000} }
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

   
   :uberjar {:jvm-opts ["-Dconf=env/prod/resources/config.edn"]
             :local-repo "/root/.m2"
             :plugins [[lein-shell "0.5.0"]]
             :prep-tasks ["compile"
                          ["run" "-m" "shadow.cljs.devtools.cli" "release" "app"]
                          ["codox"]]
             :aot :all
             :uberjar-name "tolgraven.jar"
             :source-paths ["env/prod/clj"]
             :resource-paths ["env/prod/resources" "resources"]}

   :profiles/dev {}
   :profiles/test {}
   :profiles/stage {}})
