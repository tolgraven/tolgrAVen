dev: lein with-profile dev trampoline run -m website.core
stage: lein with-profile stage run -m website.core
web: java -Dclojure.main.report=stderr -cp target/uberjar/website.jar clojure.main -m website.core
