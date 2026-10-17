# syntax=docker/dockerfile:1
# Ordinary builds pull one complete dependency image. BUILDER_IMAGE=prefab builds
# the same recipe locally on a host without registry access.
ARG BUILDER_IMAGE=registry.bux.tolgraven.se/tolgraven/builder:deps-3ec32b431e0fd9b2
ARG MAVEN_SEED_IMAGE=empty-maven-seed
FROM scratch AS empty-maven-seed
COPY package.json /root/.m2/repository/.seed
FROM --platform=$BUILDPLATFORM ${MAVEN_SEED_IMAGE} AS maven-seed
FROM --platform=$BUILDPLATFORM babashka/babashka:1.13.225@sha256:0dd6985b8492b3bde9defbd95a7e7daf7d6fcb08102df9de4f07337f99d000a2 AS babashka
FROM --platform=$BUILDPLATFORM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS node
# Runtime executables follow the target even when the compiler is native.
FROM --platform=$TARGETPLATFORM babashka/babashka:1.13.225@sha256:0dd6985b8492b3bde9defbd95a7e7daf7d6fcb08102df9de4f07337f99d000a2 AS runtime-babashka
FROM --platform=$TARGETPLATFORM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS runtime-node
FROM --platform=$TARGETPLATFORM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b AS runtime-dependencies
ENV MAGICK_THREAD_LIMIT=1
RUN apt-get update \
 && apt-get install -y --no-install-recommends imagemagick webp \
 && rm -rf /var/lib/apt/lists/*
COPY --from=runtime-babashka /usr/local/bin/bb /usr/local/bin/bb
COPY --from=runtime-node /usr/local/bin/node /usr/local/bin/node
COPY scripts/media/images.clj /app/scripts/media/images.clj
RUN mkdir /tmp/image-codec-check \
 && convert -size 16x16 xc:red /tmp/image-codec-check/image.png \
 && bb /app/scripts/media/images.clj /tmp/image-codec-check/image.png \
 && identify /tmp/image-codec-check/image.webp /tmp/image-codec-check/image.avif \
 && rm -rf /tmp/image-codec-check

FROM --platform=$BUILDPLATFORM clojure:temurin-21-lein-bookworm-slim@sha256:12d1d3d51f3aa9b4f1c643a084b6e13180caa3b83176423ad839a3b1ac493bb2 AS prefab
COPY --from=babashka /usr/local/bin/bb /usr/local/bin/bb
RUN bb --version
# Media generation and backend codec tests use the ordinary dependency prefab.
ENV MAGICK_THREAD_LIMIT=1
RUN apt-get update \
 && apt-get install -y --no-install-recommends imagemagick webp \
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
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY project.clj ./
# Keep the original BuildKit cache identity on every host. Bake its artifacts too.
RUN --mount=type=cache,id=m2,target=/root/m2,sharing=locked \
    --mount=type=bind,from=maven-seed,source=/root/.m2/repository,target=/opt/maven-seed \
    mkdir -p /tmp/lein-cache /root/.m2/repository \
 && cp -an /opt/maven-seed/. /root/m2/ \
 && printf '%s\n' '{:user {:local-repo "/root/m2"}}' > /tmp/lein-cache/profiles.clj \
 && LEIN_HOME=/tmp/lein-cache JAVA_TOOL_OPTIONS="-Xms64m -Xmx1536m -XX:ReservedCodeCacheSize=128m" \
    lein with-profile user,uberjar,provided,codox:user,uberjar deps \
 && cp -a /root/m2/. /root/.m2/repository/ \
 && rm -rf /tmp/lein-cache
RUN cp package-lock.json /opt/prefab-package-lock.json \
 && cp package.json /opt/prefab-package.json

# One published prefab carries both the compiler and the clean runtime root.
# Keep separately changing runtime tools in separately reusable upload layers.
COPY --from=runtime-dependencies --exclude=usr --exclude=opt / /opt/tolgraven-runtime/
COPY --from=runtime-dependencies /opt/ /opt/tolgraven-runtime/opt/
COPY --from=runtime-dependencies --exclude=local /usr/ /opt/tolgraven-runtime/usr/
COPY --from=runtime-dependencies /usr/local/ /opt/tolgraven-runtime/usr/local/

# Compile on the builder's native CPU. Java bytecode and browser assets are portable.
FROM --platform=$BUILDPLATFORM ${BUILDER_IMAGE} AS dependencies

FROM dependencies AS dependency-cache
WORKDIR /usr/src/app
COPY package.json package-lock.json ./
# A changed lockfile is supported even before refreshing the prefab.
RUN if ! cmp -s package-lock.json /opt/prefab-package-lock.json \
        || ! cmp -s package.json /opt/prefab-package.json; then npm ci --no-audit --no-fund; fi
COPY project.clj ./
# Rehydrate one repository snapshot before the independent compiler stages fork.
RUN --mount=type=cache,id=m2,target=/root/m2,sharing=locked \
    mkdir -p /tmp/lein-cache \
 && printf '%s\n' '{:user {:local-repo "/root/m2"}}' > /tmp/lein-cache/profiles.clj \
 && cp -an /root/.m2/repository/. /root/m2/ \
 && LEIN_HOME=/tmp/lein-cache JAVA_TOOL_OPTIONS="-Xms64m -Xmx1536m -XX:ReservedCodeCacheSize=128m" \
    lein with-profile user,uberjar,provided,codox:user,uberjar deps \
 && cp -a /root/m2/. /root/.m2/repository/ \
 && rm -rf /tmp/lein-cache
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
# Keep AOT compilation bounded; later stages reuse these classes.
ARG BUILD_CLJ_JAVA_OPTIONS="-Xms64m -Xmx768m -XX:ReservedCodeCacheSize=96m"
ARG BUILDER_IMAGE
RUN --mount=type=cache,id=tolgraven-aot-release-v1,target=/compiler-cache,sharing=locked \
    echo "Checking the complete AOT compiler input cache" \
 && COMPILER_IMAGE="${BUILDER_IMAGE}" JAVA_TOOL_OPTIONS="${BUILD_CLJ_JAVA_OPTIONS}" \
    node scripts/build/compiler-cache.mjs aot /compiler-cache \
    lein with-profile uberjar,provided update-in : assoc :target-path '"target/uberjar"' :compile-path '"target/uberjar+provided/classes"' :prep-tasks '^:replace []' -- compile

FROM backend AS docs
ARG BUILD_DOCS_JAVA_OPTIONS="-Xms64m -Xmx512m -XX:ReservedCodeCacheSize=96m"
RUN JAVA_TOOL_OPTIONS="${BUILD_DOCS_JAVA_OPTIONS}" \
    lein with-profile uberjar,provided update-in : assoc :target-path '"target/uberjar"' :compile-path '"target/uberjar+provided/classes"' :prep-tasks '^:replace []' -- docs > /tmp/codox.log 2>&1; \
    result=$?; cat /tmp/codox.log; \
    test "$result" -eq 0 && ! grep -q 'Could not generate' /tmp/codox.log

FROM docs AS frontend
RUN mkdir -p resources/public/css/website && npm run build
ARG BUILD_JAVA_OPTIONS="-Xms64m -Xmx1536m -XX:ReservedCodeCacheSize=128m"
RUN --mount=type=cache,id=tolgraven-shadow-release-v2,target=/compiler-cache,sharing=locked \
    echo "Building optimized releases with persistent compiler analysis" \
 && JAVA_TOOL_OPTIONS="${BUILD_JAVA_OPTIONS}" \
    SHADOW_CLJS='{:cache-root "/compiler-cache/shadow"}' \
    node scripts/build/compiler-cache.mjs shadow /compiler-cache \
    lein with-profile prod,provided run -m shadow.cljs.devtools.cli release app ssr return-worker document
RUN JAVA_TOOL_OPTIONS="${BUILD_JAVA_OPTIONS}" \
    lein with-profile prod,provided run -m website.build.assets

FROM backend AS build
COPY --from=docs /usr/src/app/resources/docs/codox /usr/src/app/resources/docs/codox
COPY --from=frontend /usr/src/app/resources/public /usr/src/app/resources/public
COPY --from=frontend /usr/src/app/resources/optimus /usr/src/app/resources/optimus
COPY --from=frontend /usr/src/app/target/ssr/site.js /usr/src/app/target/ssr/site.js
# All prep work is complete. In particular, jar's normal automatic clean must
# not remove the precompiled classes before packaging the finished assets.
RUN JAVA_TOOL_OPTIONS="${BUILD_CLJ_JAVA_OPTIONS}" \
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
    MAGICK_THREAD_LIMIT=1

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
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh
ENTRYPOINT ["/app/docker-entrypoint.sh"]
EXPOSE 3000
CMD ["java", "-Xms128m", "-Xmx768m", "-XX:MaxDirectMemorySize=256m", "-XX:ReservedCodeCacheSize=128m", "-XX:+ExitOnOutOfMemoryError", "-Dclojure.main.report=stderr", "-Dorg.jboss.logging.provider=slf4j", "-Dconf=env/prod/resources/config.edn", "-cp", "/app/website.jar", "clojure.main", "-m", "website.core"]
