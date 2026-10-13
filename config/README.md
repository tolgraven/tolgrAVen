# Runtime configuration examples

Active runtime files use the same name in each environment:

- Development: `env/dev/resources/config.edn`.
- Tests: `env/test/resources/config.edn`.
- Production: `env/prod/resources/config.edn`.

Dev/test files are private and ignored by Git and Docker. Production defaults are
tracked and contain no secrets; deployment credentials come from environment
variables or an external `-Dconf` file. `config/` contains tracked examples only.

For a new checkout or worktree:

```sh
mkdir -p env/dev/resources env/test/resources
cp config/dev.example.edn env/dev/resources/config.edn
cp config/test.example.edn env/test/resources/config.edn
```

Add private integration settings to those environment files. Existing private
configuration is not copied into a new worktree automatically. Read
[Runtime configuration](../doc/configuration.md) for required settings, actual
defaults, override precedence and production options. `defaults.example.edn`
shows the runtime defaults with commented alternatives.

Tool configuration stays at each tool's required location (`shadow-cljs.edn`,
`project.clj`, `bb.edn`, `.clj-kondo/config.edn`). Module/page selections belong to
`resources/site.edn` and optional CMS overrides; see [Site capabilities](../doc/site-capabilities.md).
