.DEFAULT_GOAL := help
.PHONY: help docker docker-build docker-push docker-prefab docker-registry docker-clean provision-plan provision-site
help:
	@echo 'make docker         Build locally, publish to Hetzner S3, deploy staging through Coolify'
	@echo 'make docker-build   Build locally only'
	@echo 'make docker-push    Build and publish without deploying'
	@echo 'make docker-prefab  Build/publish the cached Leiningen + Node + dependencies image'
	@echo 'make docker-clean   Remove obsolete local images and bound unused build cache to 4 GB'
	@echo 'make docker-registry Check the authenticated registry on bux'
	@echo 'make provision-plan SITE=path.json  Show a new site project plan'
	@echo 'make provision-site SITE=path.json  Create and verify isolated production/staging stacks'

docker:
	python3 scripts/docker.py deploy

docker-build:
	python3 scripts/docker.py build

docker-push:
	python3 scripts/docker.py push

docker-prefab:
	python3 scripts/docker.py prefab

.PHONY: ssr
ssr:
	lein with-profile prod run -m shadow.cljs.devtools.cli release ssr

docker-registry:
	python3 scripts/docker.py registry

provision-plan:
	@test -n "$(SITE)" || (echo "Set SITE=path/to/site.json"; exit 1)
	python3 scripts/provision-site.py plan "$(SITE)"

provision-site:
	@test -n "$(SITE)" || (echo "Set SITE=path/to/site.json"; exit 1)
	python3 scripts/provision-site.py up "$(SITE)"

docker-clean:
	python3 scripts/docker.py clean
