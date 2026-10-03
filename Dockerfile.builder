# syntax=docker/dockerfile:1
# Build once per dependency change; publish to the Hetzner S3-backed registry.
FROM node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c AS node
FROM clojure:temurin-21-lein-bookworm-slim@sha256:12d1d3d51f3aa9b4f1c643a084b6e13180caa3b83176423ad839a3b1ac493bb2
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
