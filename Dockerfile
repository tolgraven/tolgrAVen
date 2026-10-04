# syntax=docker/dockerfile:1
# Self-contained fallback for production and other hosts without the S3 registry.
# Coolify staging overrides BUILDER_IMAGE with the published prefab.
ARG BUILDER_IMAGE=prefab
FROM --platform=$BUILDPLATFORM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS node
FROM --platform=$BUILDPLATFORM clojure:temurin-21-lein-bookworm-slim@sha256:12d1d3d51f3aa9b4f1c643a084b6e13180caa3b83176423ad839a3b1ac493bb2 AS prefab
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
WORKDIR /usr/src/app
ENV LEIN_ROOT=1
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY project.clj ./
# Bake Maven artifacts into the prefab itself, so a fresh host also benefits.
RUN JAVA_TOOL_OPTIONS="-Xms64m -Xmx1536m -XX:ReservedCodeCacheSize=128m" \
    lein with-profile uberjar deps
RUN cp package-lock.json /opt/prefab-package-lock.json \
 && cp package.json /opt/prefab-package.json
# The existing Sass script starts a login shell, which resets npm's PATH.
# Link the locked project tools instead of installing another global copy.
RUN ln -s /usr/src/app/node_modules/.bin/sass /usr/local/bin/sass \
 && ln -s /usr/src/app/node_modules/.bin/postcss /usr/local/bin/postcss

# Compile on the builder's native CPU. Java bytecode and browser assets are portable.
FROM --platform=$BUILDPLATFORM ${BUILDER_IMAGE} AS build
WORKDIR /usr/src/app
COPY package.json package-lock.json ./
# A changed lockfile is supported even before refreshing the prefab.
RUN if ! cmp -s package-lock.json /opt/prefab-package-lock.json \
        || ! cmp -s package.json /opt/prefab-package.json; then npm ci --no-audit --no-fund; fi
COPY . .
RUN mkdir -p resources/public/css/tolgraven && npm run build
ARG BUILD_JAVA_OPTIONS="-Xms64m -Xmx1536m -XX:ReservedCodeCacheSize=128m"
RUN --mount=type=cache,id=tolgraven-shadow-release,target=/usr/src/app/.shadow-cljs,sharing=locked \
    JAVA_TOOL_OPTIONS="${BUILD_JAVA_OPTIONS}" lein uberjar \
 && JAVA_TOOL_OPTIONS="${BUILD_JAVA_OPTIONS}" lein with-profile prod run -m shadow.cljs.devtools.cli release blog-ssr

# This stage intentionally uses TARGETPLATFORM. The compiler runs on the Mac's
# architecture; the persistent renderer must run on the deployment host's CPU.
FROM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS ssr-node
FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b AS runtime
WORKDIR /app
RUN mkdir -p resources/public
COPY --from=ssr-node /usr/local/bin/node /usr/local/bin/node
COPY --from=build /usr/src/app/target/ssr/blog.js /app/ssr/blog.js
ENV BLOG_SSR_WORKER=/app/ssr/blog.js
COPY --from=build /usr/src/app/target/uberjar/tolgraven.jar /app/tolgraven.jar
COPY --from=build /usr/src/app/env/prod/resources/config.edn /app/env/prod/resources/config.edn
ARG VCS_REF=unknown
LABEL org.opencontainers.image.revision=$VCS_REF
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh
ENTRYPOINT ["/app/docker-entrypoint.sh"]
EXPOSE 3000
CMD ["java", "-Xms64m", "-Xmx512m", "-XX:MaxDirectMemorySize=128m", "-XX:ReservedCodeCacheSize=128m", "-XX:+ExitOnOutOfMemoryError", "-Dclojure.main.report=stderr", "-Dconf=env/prod/resources/config.edn", "-cp", "/app/tolgraven.jar", "clojure.main", "-m", "tolgraven.core"]
