# dev
run-dev:
	docker compose --env-file .envs/.env.dev -f infra/docker-compose.yml -f infra/docker-compose.dev.yml up

run-dev-down:
	docker compose --env-file .envs/.env.dev -f infra/docker-compose.yml -f infra/docker-compose.dev.yml down -v


# test
run-test:
	docker compose --env-file .envs/.env.test -f infra/docker-compose.yml -f infra/docker-compose.test.yml up

run-test-down:
	docker compose --env-file .envs/.env.test -f infra/docker-compose.yml -f infra/docker-compose.test.yml down


# prod
run-prod:
	docker compose --env-file .envs/.env.prod -f infra/docker-compose.yml -f infra/docker-compose.prod.yml up

run-prod-down:
	docker compose --env-file .envs/.env.prod -f infra/docker-compose.yml -f infra/docker-compose.prod.yml down


# build
build-prod:
	docker compose --env-file .envs/.env.prod -f infra/docker-compose.yml -f infra/docker-compose.prod.yml up --build

build-dev:
	docker compose --env-file .envs/.env.dev -f infra/docker-compose.yml -f infra/docker-compose.dev.yml up --build

build-test:
	docker compose --env-file .envs/.env.test -f infra/docker-compose.yml -f infra/docker-compose.test.yml up --build

# ==================
# TESTES
# ==================

#unit-tests
unit-test:
	./mvnw -Dtest="com/gestaosla/backend/unit/*" test

#integration-tests
integration-test:
	./mvnw -Dtest="com/gestaosla/backend/integration/*" test