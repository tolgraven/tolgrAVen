# syntax=docker/dockerfile:1
# Ordinary builds use the finished dependencies; BUILDER_IMAGE=prefab builds locally.
ARG BUILDER_IMAGE=registry.bux.tolgraven.se/tolgraven/builder:deps-567bd28d7f988bc6
ARG TOOLCHAIN_IMAGE=toolchain
# BEGIN TOOLCHAIN FALLBACK
ARG INFRA_REVISION=1
FROM --platform=$BUILDPLATFORM babashka/babashka:1.13.225@sha256:0dd6985b8492b3bde9defbd95a7e7daf7d6fcb08102df9de4f07337f99d000a2 AS babashka
FROM --platform=$BUILDPLATFORM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS node
# Runtime executables follow the target even when the compiler is native.
FROM --platform=$TARGETPLATFORM babashka/babashka:1.13.225@sha256:0dd6985b8492b3bde9defbd95a7e7daf7d6fcb08102df9de4f07337f99d000a2 AS runtime-babashka
FROM --platform=$TARGETPLATFORM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS runtime-node
FROM --platform=$TARGETPLATFORM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b AS runtime-dependencies
ENV MAGICK_THREAD_LIMIT=1 PYTHONPATH=/opt/fonttools
ARG INFRA_REVISION
RUN apt-get update \
 && apt-get install -y --no-install-recommends imagemagick webp python3-pip \
 && python3 -m pip install --no-cache-dir --no-deps --target /opt/fonttools fonttools==4.60.2 Brotli==1.2.0 \
 && python3 -c "from fontTools import subset; import brotli" \
 && rm -rf /var/lib/apt/lists/*
COPY --from=runtime-babashka /usr/local/bin/bb /usr/local/bin/bb
COPY --from=runtime-node /usr/local/bin/node /usr/local/bin/node
RUN mkdir /tmp/image-codec-check \
 && convert -size 16x16 xc:red /tmp/image-codec-check/image.png \
 && cwebp -quiet /tmp/image-codec-check/image.png -o /tmp/image-codec-check/image.webp \
 && convert /tmp/image-codec-check/image.png /tmp/image-codec-check/image.avif \
 && identify /tmp/image-codec-check/image.webp /tmp/image-codec-check/image.avif \
 && rm -rf /tmp/image-codec-check

FROM --platform=$BUILDPLATFORM clojure:temurin-21-lein-bookworm-slim@sha256:12d1d3d51f3aa9b4f1c643a084b6e13180caa3b83176423ad839a3b1ac493bb2 AS toolchain
COPY --from=babashka /usr/local/bin/bb /usr/local/bin/bb
RUN bb --version
# Media generation and backend codec tests use the ordinary dependency prefab.
ENV MAGICK_THREAD_LIMIT=1 PYTHONPATH=/opt/fonttools
ARG INFRA_REVISION
RUN apt-get update \
 && apt-get install -y --no-install-recommends imagemagick webp python3-pip \
 && python3 -m pip install --no-cache-dir --no-deps --target /opt/fonttools fonttools==4.60.2 Brotli==1.2.0 \
 && python3 -c "from fontTools import subset; import brotli" \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir /tmp/image-codec-check \
 && convert -size 16x16 xc:red /tmp/image-codec-check/image.png \
 && cwebp -quiet /tmp/image-codec-check/image.png -o /tmp/image-codec-check/image.webp \
 && convert /tmp/image-codec-check/image.png /tmp/image-codec-check/image.avif \
 && convert /tmp/image-codec-check/image.webp /tmp/image-codec-check/image.avif null: \
 && rm -rf /tmp/image-codec-check
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
WORKDIR /usr/src/app
ENV LEIN_ROOT=1
# Maven artifacts belong only in the persistent BuildKit m2 mount.
RUN mkdir -p /root/.m2 \
 && rm -rf /root/.m2/repository \
 && ln -s /root/m2 /root/.m2/repository
# The infrastructure image carries the tools and the clean runtime root.
# Keep separately changing runtime tools in separately reusable upload layers.
COPY --from=runtime-dependencies --exclude=usr --exclude=opt / /opt/tolgraven-runtime/
COPY --from=runtime-dependencies /opt/ /opt/tolgraven-runtime/opt/
COPY --from=runtime-dependencies --exclude=local /usr/ /opt/tolgraven-runtime/usr/
COPY --from=runtime-dependencies /usr/local/ /opt/tolgraven-runtime/usr/local/
# END TOOLCHAIN FALLBACK

# BEGIN DEPENDENCY FALLBACK
FROM --platform=$BUILDPLATFORM ${TOOLCHAIN_IMAGE} AS prefab
WORKDIR /usr/src/app
COPY package.json package-lock.json ./
RUN --mount=type=cache,id=npm,target=/root/.npm,sharing=locked \
    npm ci --no-audit --no-fund \
 && cp package-lock.json /opt/prefab-package-lock.json \
 && cp package.json /opt/prefab-package.json
# END DEPENDENCY FALLBACK

# Compile on the builder's native CPU. Java bytecode and browser assets are portable.
FROM --platform=$BUILDPLATFORM ${BUILDER_IMAGE} AS dependencies

FROM dependencies AS dependency-cache
WORKDIR /usr/src/app
COPY package.json package-lock.json ./
# A changed lockfile is supported even before refreshing the prefab.
RUN --mount=type=cache,id=npm,target=/root/.npm,sharing=locked \
    if ! cmp -s package-lock.json /opt/prefab-package-lock.json \
        || ! cmp -s package.json /opt/prefab-package.json; then npm ci --no-audit --no-fund; fi
COPY project.clj ./
FROM dependency-cache AS source
COPY . .
ARG STRAPI_URL
# Snapshot capabilities/CSS per source build. Force rebuild for same-source CMS changes.
RUN --mount=type=secret,id=STRAPI_URL,env=STRAPI_URL \
    --mount=type=secret,id=STRAPI_READ_TOKEN,env=STRAPI_READ_TOKEN \
    bb -cp scripts:src/cljc:resources -m build.resolve-site
# Sequence compiler JVMs on hosts that also run the database/CMS stack.
# Codox reuses AOT classes; Shadow keeps its separate output and cache paths.
FROM source AS backend
# Later stages reuse the completed AOT classes.
ARG BUILD_CLJ_JAVA_OPTIONS="-Xms128m -XX:MaxRAMPercentage=60 -XX:ReservedCodeCacheSize=256m"
ARG BUILDER_IMAGE
RUN --mount=type=cache,id=m2,target=/root/m2,sharing=locked \
    --mount=type=cache,id=tolgraven-aot-release-v1,target=/compiler-cache,sharing=locked \
    echo "Checking the complete AOT compiler input cache" \
 && set -- lein with-profile uberjar,provided update-in : assoc :target-path '"target/uberjar"' :compile-path '"target/uberjar+provided/classes"' :prep-tasks '^:replace []' -- \
 && JAVA_TOOL_OPTIONS="${BUILD_CLJ_JAVA_OPTIONS}" "$@" classpath > /tmp/aot-classpath \
 && COMPILER_IMAGE="${BUILDER_IMAGE}" JAVA_TOOL_OPTIONS="${BUILD_CLJ_JAVA_OPTIONS}" \
    node scripts/build/compiler-cache.mjs aot /compiler-cache /tmp/aot-classpath "$@" compile

FROM backend AS docs
ARG BUILD_DOCS_JAVA_OPTIONS="-Xms128m -XX:MaxRAMPercentage=60 -XX:ReservedCodeCacheSize=256m"
RUN --mount=type=cache,id=m2,target=/root/m2,sharing=locked \
    --mount=type=cache,id=tolgraven-docs-release-v1,target=/compiler-cache,sharing=locked \
    COMPILER_IMAGE="${BUILDER_IMAGE}" JAVA_TOOL_OPTIONS="${BUILD_DOCS_JAVA_OPTIONS}" \
    node scripts/build/compiler-cache.mjs docs /compiler-cache \
    sh -c 'lein with-profile uberjar,provided update-in : assoc :target-path '\''"target/uberjar"'\'' :compile-path '\''"target/uberjar+provided/classes"'\'' :prep-tasks '\''^:replace []'\'' -- docs > /tmp/codox.log 2>&1; result=$?; cat /tmp/codox.log; test "$result" -eq 0 && ! grep -q "Could not generate" /tmp/codox.log'

FROM docs AS frontend
RUN mkdir -p resources/public/css/website && npm run build
ARG BUILD_JAVA_OPTIONS="-Xms256m -XX:MaxRAMPercentage=60 -XX:ReservedCodeCacheSize=256m"
RUN --mount=type=cache,id=m2,target=/root/m2,sharing=locked \
    --mount=type=cache,id=tolgraven-shadow-release-v2,target=/compiler-cache,sharing=locked \
    echo "Building optimized releases with persistent compiler analysis" \
 && COMPILER_IMAGE="${BUILDER_IMAGE}" JAVA_TOOL_OPTIONS="${BUILD_JAVA_OPTIONS}" \
    SHADOW_CLJS='{:cache-root "/compiler-cache/shadow"}' \
    node scripts/build/compiler-cache.mjs shadow /compiler-cache \
    lein with-profile prod,provided run -m shadow.cljs.devtools.cli release app ssr return-worker document
RUN --mount=type=cache,id=m2,target=/root/m2,sharing=locked \
    JAVA_TOOL_OPTIONS="${BUILD_JAVA_OPTIONS}" \
    lein with-profile prod,provided run -m website.build.assets

FROM backend AS build
COPY --from=docs /usr/src/app/resources/docs/codox /usr/src/app/resources/docs/codox
COPY --from=frontend /usr/src/app/resources/public /usr/src/app/resources/public
COPY --from=frontend /usr/src/app/resources/optimus /usr/src/app/resources/optimus
COPY --from=frontend /usr/src/app/target/ssr/site.js /usr/src/app/target/ssr/site.js
# All prep work is complete. In particular, jar's normal automatic clean must
# not remove the precompiled classes before packaging the finished assets.
RUN --mount=type=cache,id=m2,target=/root/m2,sharing=locked \
    JAVA_TOOL_OPTIONS="${BUILD_CLJ_JAVA_OPTIONS}" \
    lein with-profile uberjar,provided update-in : assoc :target-path '"target/uberjar"' :compile-path '"target/uberjar+provided/classes"' :prep-tasks '^:replace []' :auto-clean false -- uberjar

# Compilation uses BUILDPLATFORM; executable runtime files must use TARGETPLATFORM.
FROM --platform=$TARGETPLATFORM ${BUILDER_IMAGE} AS runtime-prefab
FROM scratch AS media-tools
COPY --from=runtime-prefab /opt/tolgraven-runtime/ /
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8 \
    MAGICK_THREAD_LIMIT=1 \
    PYTHONPATH=/opt/fonttools

FROM media-tools AS runtime
WORKDIR /app
RUN mkdir -p resources/public
COPY --from=build /usr/src/app/target/ssr/site.js /app/ssr/site.js
COPY --from=build /usr/src/app/target/uberjar/website.jar /app/website.jar
COPY --from=build /usr/src/app/env/prod/resources/config.edn /app/env/prod/resources/config.edn
ARG VCS_REF=unknown
ARG SOURCE_COMMIT
ARG CAPROVER_GIT_COMMIT_SHA
ARG DEPLOY_REPOSITORY=tolgraven/tolgrAVen
RUN printf '%s\n' "${CAPROVER_GIT_COMMIT_SHA:-${SOURCE_COMMIT:-$VCS_REF}}" > /app/revision \
 && printf '%s\n' "$DEPLOY_REPOSITORY" > /app/repository
LABEL org.opencontainers.image.revision=$VCS_REF
COPY scripts/media/images.clj /app/scripts/media/images.clj
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh
ENTRYPOINT ["/app/docker-entrypoint.sh"]
EXPOSE 3000
CMD ["java", "-Xms128m", "-Xmx768m", "-XX:MaxDirectMemorySize=256m", "-XX:ReservedCodeCacheSize=128m", "-XX:+ExitOnOutOfMemoryError", "-Dclojure.main.report=stderr", "-Dorg.jboss.logging.provider=slf4j", "-Dconf=env/prod/resources/config.edn", "-cp", "/app/website.jar", "clojure.main", "-m", "website.core"]
