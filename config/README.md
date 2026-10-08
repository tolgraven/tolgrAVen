# Configuration ownership

Private machine overrides live here and are ignored by Git and Docker:

- `local.dev.edn`: read by `lein repl` and the development profile.
- `local.test.edn`: read by the test profile.
- `local.edn`: optional manual override; use `-Dconf=config/local.edn` explicitly.

Copy an example to its corresponding local file on a new checkout. Environment
classpath defaults remain in `env/<environment>/resources/config.edn`; cprop
merges the selected override, Mount arguments, system properties, then environment
variables. Production defaults contain no secrets. Inject deployment secrets as
environment variables or an external `-Dconf` file, never Docker build inputs.

Tool configuration stays at each tool's required location (`shadow-cljs.edn`,
`project.clj`, `bb.edn`, `.clj-kondo/config.edn`). These are not runtime overrides.
An ignored `local.dev-legacy.edn`, if present, preserves an old unused resource
copy; it is not automatically loaded.
