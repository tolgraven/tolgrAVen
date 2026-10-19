# syntax=docker/dockerfile:1
# Build once per dependency change; publish to the Hetzner S3-backed registry.
ARG MAVEN_SEED_IMAGE=empty-maven-seed
FROM scratch AS empty-maven-seed
COPY package.json /root/.m2/repository/.seed
FROM --platform=$BUILDPLATFORM ${MAVEN_SEED_IMAGE} AS maven-seed
FROM --platform=$BUILDPLATFORM babashka/babashka:1.13.225@sha256:0dd6985b8492b3bde9defbd95a7e7daf7d6fcb08102df9de4f07337f99d000a2 AS babashka
FROM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS node
# Runtime executables follow the target even when the compiler is native.
FROM --platform=$TARGETPLATFORM babashka/babashka:1.13.225@sha256:0dd6985b8492b3bde9defbd95a7e7daf7d6fcb08102df9de4f07337f99d000a2 AS runtime-babashka
FROM --platform=$TARGETPLATFORM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS runtime-node
FROM --platform=$TARGETPLATFORM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b AS runtime-dependencies
ENV MAGICK_THREAD_LIMIT=1 PYTHONPATH=/opt/fonttools
RUN apt-get update \
 && apt-get install -y --no-install-recommends imagemagick webp python3-pip \
 && python3 -m pip install --no-cache-dir --no-deps --target /opt/fonttools fonttools==4.60.2 Brotli==1.2.0 \
 && python3 -c "from fontTools import subset; import brotli" \
 && rm -rf /var/lib/apt/lists/*
COPY --from=runtime-babashka /usr/local/bin/bb /usr/local/bin/bb
COPY --from=runtime-node /usr/local/bin/node /usr/local/bin/node
COPY scripts/media/images.clj /app/scripts/media/images.clj
RUN mkdir /tmp/image-codec-check \
 && convert -size 16x16 xc:red /tmp/image-codec-check/image.png \
 && bb /app/scripts/media/images.clj /tmp/image-codec-check/image.png \
 && identify /tmp/image-codec-check/image.webp /tmp/image-codec-check/image.avif \
 && rm -rf /tmp/image-codec-check

FROM clojure:temurin-21-lein-bookworm-slim@sha256:12d1d3d51f3aa9b4f1c643a084b6e13180caa3b83176423ad839a3b1ac493bb2
COPY --from=babashka /usr/local/bin/bb /usr/local/bin/bb
RUN bb --version
# Media generation and backend codec tests use the ordinary dependency prefab.
ENV MAGICK_THREAD_LIMIT=1 PYTHONPATH=/opt/fonttools
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
