#!/usr/bin/env bash
set -euo pipefail
# Run this through tolgraven-cpu; CSS and configured targets run sequentially.
npm run build
if [[ "${1:-}" == --hydration || "${1:-}" == --production-hydration ]]; then
  lein with-profile prod,provided run -m shadow.cljs.devtools.cli release ssr
  test_names='{:ns-regexp "website\\.(markdown-components|blog-editor|blog-ssr)-test$"}'
else
  test_names='{:ns-regexp "website\\.(markdown-components|blog-editor)-test$"}'
fi
if [[ "${1:-}" == --production-hydration ]]; then
  # A separate isolated run retains the ordinary development suite output.
  lein with-profile +project/test,+provided run -m shadow.cljs.devtools.cli --config-merge '{:runner-ns website.authored-hydration-runner :ns-regexp "website\\.authored-hydration-test$" :compiler-options {:reader-features #{:browser :browser-test}}}' release app-test
  mkdir -p resources/public/test-release
  cp resources/public/js/tests/js/test.js resources/public/test-release/test.js
else
  lein with-profile +project/test,+provided run -m shadow.cljs.devtools.cli --config-merge "$test_names" compile app-test
fi
if [[ "${1:-}" == --hydration || "${1:-}" == --production-hydration ]]; then
  # Browser-test owns and may clear its output directory during configuration.
  bb ssr:fixtures
fi
