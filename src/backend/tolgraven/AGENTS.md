# Server adapters

Keep this guide current. See `doc/ssr.md`, `doc/strapi-content.md`, and
`doc/site-provisioning.md` for operating and configuration details.

- Feature-owned server adapters live under `modules/<feature>/`; shared platform
  adapters retain their `content/`, `supabase/`, and `ssr/` ownership.
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
- Avatar uploads normalize/validate PNG before bounded `media/image` conversion.
  Publish versioned PNG/WebP/AVIF to Supabase Storage before updating the profile;
  its mounted object backend owns persistence, never the web container filesystem.
- Do not stop a user's REPL or rebuild a watched Shadow target with another process.

- Reitit uses shared Malli parameter schemas. Internal/response validation follows
  runtime configuration; request coercion remains active. Coercion error handlers
  must not log full exception data or return request/response values.

- `ssr/contract_schema.cljc` is the JVM/Node protocol boundary. Hydration and
  local-return adapters live in frontend CLJS; module-owned snapshot conversion
  is shared by the Node renderer and browser, not required by the JVM server.
