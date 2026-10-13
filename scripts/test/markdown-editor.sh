#!/usr/bin/env bash
set -euo pipefail
# Run this through tolgraven-cpu; CSS and the focused compiler run sequentially.
npm run build
lein with-profile +project/test,+provided run -m shadow.cljs.devtools.cli --config-merge '{:ns-regexp "tolgraven\\.(markdown-components|blog-editor)-test$"}' compile app-test
