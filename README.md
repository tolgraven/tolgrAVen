# tolgraven

The code for my main website at https://tolgraven.se

## Prerequisites

You will need [Leiningen][1] 2.0 or above installed.

[1]: https://github.com/technomancy/leiningen

## Setup and development

Use Java 21 or newer, Leiningen, and Node.js 22. Initialize the pinned Firebase
checkout before building, then install the lockfile and build CSS:

```sh
git submodule update --init --recursive
npm ci
npm run build
lein repl
```

The development server runs at http://localhost:4000. Run `npm run dev` in a
second terminal to watch CSS. In the Clojure REPL, `(cljs-repl)` selects the
`:app-dev` browser runtime. `(restart-handler)` reloads the Ring handler after
reloading changed backend namespaces.

## Validation

```sh
lein test
lein with-profile +test run -m shadow.cljs.devtools.cli compile app-test
python3 -m http.server 4002 --bind 127.0.0.1 --directory resources/public/js/tests
```

Open http://localhost:4002 to run the browser tests. They cover the split route
tree, asynchronous navigation, module initialization, and search completions.
After recompiling, use a hard reload to avoid a cached test bundle.

Compile production JavaScript without replacing a running development build:

```sh
lein with-profile -dev,+prod run -m shadow.cljs.devtools.cli release app --config-merge '{:output-dir "target/production-js"}'
```

`lein uberjar` builds the deployable server, production JavaScript, and Codox.
Both browser builds use the same lazy module graph. Pull requests validate CSS;
the deployment job runs only for pushes to `master`.

See [the integration and recovery notes](doc/integration-20260909.md) for the
preserved local work and the changes brought together in this branch.

## License

Copyright © 2020-2026 Joen Tolgraven

<img width="1033" height="1014" alt="image" src="https://github.com/user-attachments/assets/df07bdb0-0af1-4dab-a920-652882f08323" />

