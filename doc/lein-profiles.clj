;; Personal Lein profile used on this workstation. Credentials stay in AWS config.
{:user
 {:plugins [[lein-pprint "1.3.2"]
            [lein-ancient "1.0.0-RC3"]
            [lein-shell "0.5.0"]]
  :deploy-repositories [["private-local" {:url "file:.deploy-m2/"
                                          :no-auth true
                                          :sign-releases false}]]
  :aliases {"deploy-private"
            ["do"
             ["shell" "mkdir" "-p" ".deploy-m2"]
             ;; Seed Maven metadata so publishing another version retains history.
             ["shell" "aws" "--profile" "hetzner"
              "--endpoint-url" "https://hel1.your-objectstorage.com"
              "s3" "sync" "s3://tolgraven/m2/releases/" ".deploy-m2/"]
             ["clean"]
             ["deploy" "private-local"]
             ["shell" "aws" "--profile" "hetzner"
              "--endpoint-url" "https://hel1.your-objectstorage.com"
              "s3" "sync" ".deploy-m2/" "s3://tolgraven/m2/releases/"
              "--acl" "public-read"]
             ["shell" "trash" ".deploy-m2"]]}}}
