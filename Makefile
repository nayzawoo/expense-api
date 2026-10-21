.DEFAULT_GOAL := help

MVN := ./mvnw --no-transfer-progress
COMPOSE := docker compose

.PHONY: help compile build run test test-focused verify smoke check clean up down logs ps health

help: ## Show available shortcuts
	@awk 'BEGIN { FS = ":.*## " } /^[a-zA-Z_-]+:.*## / { printf "  %-16s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

compile: ## Compile application sources
	$(MVN) compile

build: ## Package the application without running tests
	$(MVN) -DskipTests package

run: ## Run locally (requires a separately provisioned database)
	$(MVN) spring-boot:run

test: ## Run unit tests only
	$(MVN) test

test-focused: ## Run selected integration tests: make test-focused TEST=ProfileIT
	@test -n "$(TEST)" || { echo "Usage: make test-focused TEST='ProfileIT#methodName'" >&2; exit 1; }
	sh scripts/test-focused.sh '$(TEST)'

verify: ## Run full Maven verification, including integration tests (requires Docker)
	$(MVN) --batch-mode verify

smoke: ## Run isolated container smoke tests (requires Python 3 and Docker)
	python3 scripts/container-smoke.py

check: ## Run Maven verification followed by container smoke tests
	$(MAKE) verify
	$(MAKE) smoke

clean: ## Remove Maven build outputs
	$(MVN) clean

up: ## Build and start Compose services, waiting for health
	$(COMPOSE) up --build -d --wait

down: ## Stop Compose services and retain database data
	$(COMPOSE) down

logs: ## Follow Compose service logs
	$(COMPOSE) logs -f

ps: ## Show Compose service status
	$(COMPOSE) ps

health: ## Check API readiness and liveness inside the container
	$(COMPOSE) exec -T api curl --fail --silent --show-error http://localhost:8080/actuator/health/readiness
	$(COMPOSE) exec -T api curl --fail --silent --show-error http://localhost:8080/actuator/health/liveness
