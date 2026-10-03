.DEFAULT_GOAL := help
.PHONY: help docker docker-build docker-push docker-prefab docker-registry
help:
	@echo 'make docker         Build locally, publish to Hetzner S3, deploy staging through Coolify'
	@echo 'make docker-build   Build locally only'
	@echo 'make docker-push    Build and publish without deploying'
	@echo 'make docker-prefab  Build/publish the cached Leiningen + Node + dependencies image'
	@echo 'make docker-registry Start the private loopback S3 registry locally'

docker:
	python3 scripts/docker.py deploy

docker-build:
	python3 scripts/docker.py build

docker-push:
	python3 scripts/docker.py push

docker-prefab:
	python3 scripts/docker.py prefab

docker-registry:
	python3 scripts/docker.py registry
