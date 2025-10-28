# LEININGEN
## env

### Private Maven Repo
For forks (like re-frame-firebase) where haven't been able to get stuff merged,
and using `checkouts` locally (in git as submodule), for server builds we use a private repo from an s3 bucket.

This involves the setup in `project.clj` as well as some stuff in `profiles.clj`:
```clojure
{:deploy-repositories [["private-local" {:url "file:.deploy-m2" ; for checkouts/forks, syncing to private s3 m2 repo
                                          :no-auth true
                                          :sign-releases false}]]
 :aliases {"deploy-private" ["do" 
                              ["shell" "mkdir" "-p" ".deploy-m2"]
                              ["shell" "bash" "-c" "lein clean && lein deploy private-local"]
                              ["shell" "aws" "--profile" "hetzner" "--endpoint-url" "https://hel1.your-objectstorage.com" "s3" "sync" ".deploy-m2/" "s3://tolgraven/m2/releases/" "--acl" "public-read"]
                              ["shell" "trash" ".deploy-m2"]]}}
```   
and running `lein deploy-private` in those repos, as well as setting the appropriate env vars on the server.
Also requires `aws` cli set up with a hetzner profile for this specific example.
