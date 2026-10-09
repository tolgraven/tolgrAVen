(defproject tolgraven "0.1.0-SNAPSHOT"
  :description "tolgrAVen.se website"
  :url "https://tolgraven.se"

  ;; Application-owned dependencies; Shadow owns its compiler graph in :provided.
  :dependencies [[org.clojure/clojure "1.12.6"]
                 [org.clojure/data.json "2.5.2"]
                 [org.clojure/tools.cli "1.4.256"]
                 [nrepl "1.8.0"]
                 [com.cognitect/transit-clj "1.1.363"]
                 [org.clojure/core.async "1.9.865"]
                 [hiccup "2.0.0"]
                 [expound "0.9.0"]
                 [metosin/malli "0.20.2"]

                 [org.clojure/java.jdbc "0.7.12"]
                 [org.postgresql/postgresql "42.7.13"]
                 [cprop "0.1.21"] ;env loading
                 [clj-http "3.13.1"]
                 [cheshire "6.2.0"] ; clj-http's :as :json / JSON request adapter

                 [luminus-transit "0.1.6"]
                 [luminus-undertow "0.1.18"]

                 [markdown-clj "1.12.10"]

                 [metosin/jsonista "1.0.1"]
                 [metosin/muuntaja "0.6.12"]
                 [metosin/reitit-core "0.11.0"]
                 [metosin/reitit-ring "0.11.0"]
                 [metosin/reitit-middleware "0.11.0"]
                 [metosin/reitit-malli "0.11.0"]
                 [metosin/reitit-swagger "0.11.0"]
                 [metosin/reitit-swagger-ui "0.11.0"]
                 [metosin/reitit-frontend "0.11.0"]
                 [metosin/reitit-dev "0.11.0"]

                 [mount "0.1.24"]

                 [org.clojure/tools.logging "1.3.1"]
                 [com.taoensso/timbre "6.8.0" :exclusions [org.clojure/tools.reader]]
                 [com.taoensso/encore "3.172.1"]
                 [com.fzakaria/slf4j-timbre "0.4.1" :exclusions [org.slf4j/slf4j-api]] ; Java SLF4J callers use Timbre.
                 [io.aviso/pretty "1.4.4"] ;pretty exceptions, pretty logging...

                 [ring/ring-core "1.15.5"]
                 [ring/ring-defaults "0.7.1"]
                 [amalloy/ring-gzip-middleware "0.1.4"]
                 [ring-partial-content "2.1.0"] ; handle safari video playback / 206 response
                 [metosin/ring-http-response "0.9.5"]
                 
                 [optimus "2026.05.27"] ;optimization of assets
                 [optimus-img-transform "0.3.1"]

                 [sitemap "0.4.0"]]

  ;; Align shared transitive families rather than depending on traversal order.
  :managed-dependencies [;; Codox injects an old analyzer: align it with Shadow 3.5.5.
                         [org.clojure/clojurescript "1.12.145"]
                         [org.clojure/core.rrb-vector "0.2.1"]
                         [commons-io "2.21.0"]
                         [org.clojure/core.memoize "1.1.266"]
                         [org.clojure/core.cache "1.1.234"]
                         [org.clojure/data.priority-map "1.2.0"]
                         [cheshire "6.2.0"]
                         [com.fasterxml.jackson.core/jackson-core "2.22.2"]
                         [com.fasterxml.jackson.core/jackson-databind "2.22.2"]
                         [com.fasterxml.jackson.core/jackson-annotations "2.22"]
                         [com.fasterxml.jackson.dataformat/jackson-dataformat-smile "2.22.2"]
                         [com.fasterxml.jackson.dataformat/jackson-dataformat-cbor "2.22.2"]
                         [com.fasterxml.jackson.datatype/jackson-datatype-jsr310 "2.22.2"]]

  :min-lein-version "2.0.0"

  :source-paths ["src/backend" "src/frontend" "src/cljc"]
  :test-paths ["test/clj"]
  :resource-paths ["resources"]
  ;; Git ignores do not affect resource packaging in local jar builds.
  :jar-exclusions [#"^public/js/tests/"
                   #"(^|/)AGENTS\.md$"
                   #"^public/js/compiled/out/cljs-runtime/"
                   #"(?i)(^|/)firebase/.*(firebase-adminsdk|SECRETS).*"
                   #"(^|/)(dev-config|test-config)\.edn$"]
  :target-path "target/%s/"
  :main ^:skip-aot tolgraven.core

  :repositories
  [["private" {:url "https://tolgraven.hel1.your-objectstorage.com/m2/releases/"
               :no-auth true}]] ; uses injected credentials

  :plugins [[lein-codox "0.10.8"]]

  :codox
  {:language :clojurescript
   :output-path "resources/docs/codox"
   :metadata {:doc/format :markdown}
   :namespaces [#"^tolgraven\."]
   :source-uri "https://github.com/tolgraven/tolgraven/blob/master/{filepath}#L{line}"}

  :profiles
  {:provided {:dependencies [[thheller/shadow-cljs "3.5.5"]
                             [com.cognitect/transit-cljs "0.8.280"]
                             [cljs-ajax "0.8.4"]
                             [com.andrewmcveigh/cljs-time "0.5.2"]
                             [re-frame "1.4.7"]
                             [day8.re-frame/http-fx "0.2.4"]
                             [breaking-point "0.1.2"]
                             [reagent "2.0.1"]]}
   :dev           [:project/dev :profiles/dev]
   :test          [:project/dev :project/test :profiles/test]
   :stage         [:uberjar :profiles/stage]

   :project/dev  {:jvm-opts ["-Dconf=config/local.dev.edn" "-XX:-OmitStackTraceInFastThrow"]
                  :dependencies [[binaryage/devtools "1.0.7"]
                                 [prone "2021-04-23"]
                                 [ring/ring-devel "1.15.5"]]
                  :plugins      [[cider/cider-nrepl "0.57.0"]]

                  :source-paths ["env/dev/clj" "env/dev/cljs"]
                  :resource-paths ["env/dev/resources"]
                  :repl-options {:welcome (println "in DEV profile")
                                 :init-ns user
                                 :port 7000
                                 :init (start)
                                 :nrepl-middleware [cider.piggieback/wrap-cljs-repl
                                                    shadow.cljs.devtools.server.nrepl/middleware]
                                 :timeout 30000}
                  }
   :project/test {:dependencies [[ring/ring-mock "0.6.2"]]
                  :source-paths ["test/cljs"]
                  :jvm-opts ["-Dconf=config/local.test.edn"]
                  :resource-paths ["env/test/resources"]}

   :uberjar {:jvm-opts ["-Dconf=env/prod/resources/config.edn"]
             :prep-tasks ["compile"
                          ["run" "-m" "shadow.cljs.devtools.cli" "release" "app" "return-worker"]
                          ["codox"]]
             :aot :all
             :uberjar-name "tolgraven.jar"
             :source-paths ["env/prod/clj" "env/prod/cljs"]
             :resource-paths ["env/prod/resources" "resources"]}

   :prod {:source-paths ["env/prod/clj" "env/prod/cljs"]
          :resource-paths ["env/prod/resources"]}

   ;; Codox's bundled CLJS 1.7 analyzer must not override Shadow's compiler.
   ;; Lein Codox merges this profile only while generating documentation.
   :codox {:dependencies [[codox "0.10.8" :exclusions [org.clojure/clojurescript]]]}

   ;; Optional tools/experiments remain available without burdening every build.
   :s3-publish {:plugins [[s3-wagon-private "1.3.5"]]}
   :legacy-debug {:managed-dependencies [[mvxcvi/puget "1.3.2"]]
                  :dependencies [[re-frisk "1.7.1"]
                                 [day8.re-frame/re-frame-10x "1.12.3" :exclusions [superstructor/re-highlight]]]}
   :experiments {:source-paths ["experiments/clj"]
                 :dependencies [[honeysql "1.0.461"]
                                [differ "0.3.3"]
                                [ring-ratelimit "0.2.3"]
                                [radicalzephyr/ring.middleware.logger "0.6.0"]
                                [toyokumo/ring-middleware-csp "0.4.63"]]}
   :profiles/dev {}
   :profiles/test {}
   :profiles/stage {}})
