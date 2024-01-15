# ---------------------------------------------------------------------------
# DineHub — common commands.
#
# Every target is a thin, readable wrapper over an explicit command. If you want
# to know what `make deploy` does, the answer is three lines below, not in a
# script three directories away.
# ---------------------------------------------------------------------------

ENV        ?= dev
VERSION    ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
NAMESPACE  := dinehub-$(ENV)
HELM_CHART := deploy/helm/dinehub
VALUES     := deploy/helm/values-$(ENV).yaml
COMPOSE    := docker compose -f deploy/docker-compose.yml

# Maven and Node run in containers, so the only thing you need installed is
# Docker. A toolchain that works on one laptop and not another is not a
# toolchain.
MVN  := docker run --rm -v "$(PWD)":/w -v dinehub-m2:/root/.m2 -w /w maven:3.9-eclipse-temurin-21 mvn
NPM  := docker run --rm -v "$(PWD)/web":/w -w /w node:20-alpine npm

.DEFAULT_GOAL := help
.PHONY: help build test test-unit test-it lint up down logs ps clean \
        images cluster deploy smoke rollback status observability-up observability-down

help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "  Variables: ENV=dev|test|prod   VERSION=<tag>"

# --- Build ----------------------------------------------------------------

build: ## Build all services and the web app
	$(MVN) -B -q install -DskipTests
	@test -d web/node_modules || $(NPM) ci
	$(NPM) run build

test: test-unit test-it ## Unit and integration tests, with the coverage gate

test-unit: ## Unit tests only, with the 70% coverage gate
	$(MVN) -B verify -DskipITs

test-it: ## Integration tests (Testcontainers — needs Docker)
	$(MVN) -B verify -DskipUnitTests=false

lint: ## Lint the Angular app and validate the Helm charts
	$(NPM) run lint
	helm lint $(HELM_CHART) --values $(VALUES)

images: ## Build every container image locally
	./deploy/scripts/gen-dockerfiles.sh
	@for svc in api-gateway auth-service menu-service; do \
	  echo "building dinehub/$$svc:$(VERSION)"; \
	  docker build -q -f services/$$svc/Dockerfile \
	    -t dinehub/$${svc}:$(VERSION) -t dinehub/$${svc}:local . >/dev/null; \
	done
	@echo "images built at $(VERSION)"

# --- Local stack ----------------------------------------------------------

up: ## Start the full stack locally (http://localhost)
	$(COMPOSE) up -d --build
	@echo ""
	@echo "  Gateway       http://localhost:8080"
	@echo "  Swagger (auth) http://localhost:8081/swagger-ui.html"
	@echo "  RabbitMQ UI   http://localhost:15672  (dinehub / dinehub)"
	@echo ""
	@echo "  Demo logins — password DineHub2024! for all three:"
	@echo "    customer@dinehub.local   CUSTOMER"
	@echo "    chef@dinehub.local       KITCHEN"
	@echo "    admin@dinehub.local      ADMIN"
	@echo ""

down: ## Stop the local stack
	$(COMPOSE) down

down-clean: ## Stop the local stack and delete its data
	$(COMPOSE) down -v

logs: ## Follow logs from the local stack
	$(COMPOSE) logs -f --tail=100

ps: ## Show local stack status
	$(COMPOSE) ps

observability-up: ## Start Prometheus, Grafana and Loki alongside the stack
	$(COMPOSE) -f observability/docker-compose.observability.yml up -d
	@echo "  Grafana     http://localhost:3000  (admin / admin)"
	@echo "  Prometheus  http://localhost:9090"

observability-down: ## Stop the observability stack
	$(COMPOSE) -f observability/docker-compose.observability.yml down

# --- Kubernetes -----------------------------------------------------------

cluster: ## Create a local k3d cluster with dev, test and prod namespaces
	./deploy/scripts/create-local-cluster.sh

deploy: ## Deploy to a namespace (ENV=dev|test|prod)
	helm upgrade --install dinehub $(HELM_CHART) \
	  --namespace $(NAMESPACE) --create-namespace \
	  --values $(VALUES) \
	  --set global.image.tag=$(VERSION) \
	  --atomic --wait --timeout 10m
	@echo "deployed $(VERSION) to $(NAMESPACE)"

smoke: ## Run smoke tests against an environment (ENV=dev|test|prod)
	./deploy/scripts/smoke-test.sh --env $(ENV)

rollback: ## Roll back the last release in an environment
	./deploy/scripts/rollback.sh --env $(ENV)

status: ## Show what is deployed in an environment
	@helm status dinehub --namespace $(NAMESPACE) 2>/dev/null || echo "nothing deployed in $(NAMESPACE)"
	@kubectl get pods --namespace $(NAMESPACE) 2>/dev/null || true

clean: ## Remove build artefacts
	$(MVN) -B -q clean
	rm -rf web/dist web/node_modules/.cache
