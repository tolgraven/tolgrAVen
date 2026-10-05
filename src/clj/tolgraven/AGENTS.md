# Server adapters

Keep this guide current. See `doc/blog-ssr.md`, `doc/strapi-content.md`, and
`doc/site-provisioning.md` for operating and configuration details.

- Use shared CLJC data contracts and page declarations. Generic SSR must not grow
  page-specific SQL/REST predicates or duplicate component markup.
- Source acquisition runs concurrently under bounded limits; identical requests
  share an in-flight task. Node workers perform isolated React rendering only.
- Cache public HTML with its exact data snapshot. Never cache a request's CSRF token
  or personalized shell as a shared response. Errors cannot validate old cache entries.
- Streaming uses Hiccup document parts and the existing flush-aware Ring body;
  do not split serialized pages by marker strings. Headers cannot change after flush.
- Validate/coerce requests at the Ring boundary; handlers consume parsed parameters.
  Authentication/authorization and input protection remain active in production.
- Secrets remain in server adapters/configuration. Runtime public settings must
  expose only public origins/keys and allowed options.
- Do not stop a user's REPL or rebuild a watched Shadow target with another process.
