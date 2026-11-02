# syntax=docker/dockerfile:1
# Node dependencies reuse a separately pinned infrastructure image.
ARG TOOLCHAIN_IMAGE=registry.bux.tolgraven.se/tolgraven/builder:toolchain-10a21ee6505af181
FROM --platform=$BUILDPLATFORM ${TOOLCHAIN_IMAGE} AS prefab
WORKDIR /usr/src/app
COPY package.json package-lock.json ./
RUN --mount=type=cache,id=npm,target=/root/.npm,sharing=locked \
    npm ci --no-audit --no-fund \
 && cp package-lock.json /opt/prefab-package-lock.json \
 && cp package.json /opt/prefab-package.json
