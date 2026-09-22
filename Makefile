# dev
run-dev:
	docker compose --env-file .envs/.env.dev -f docker-compose.yml -f docker-compose.dev.yml up

run-dev-down:
	docker compose --env-file .envs/.env.dev -f docker-compose.yml -f docker-compose.dev.yml down


# test
run-test:
	docker compose --env-file .envs/.env.test -f docker-compose.yml -f docker-compose.test.yml up

run-test-down:
	docker compose --env-file .envs/.env.test -f docker-compose.yml -f docker-compose.test.yml down


# prod
run-prod:
	docker compose --env-file .envs/.env.prod -f docker-compose.yml -f docker-compose.prod.yml up

run-prod-down:
	docker compose --env-file .envs/.env.prod -f docker-compose.yml -f docker-compose.prod.yml down


# build
build-prod:
	docker compose --env-file .envs/.env.prod -f docker-compose.yml -f docker-compose.prod.yml up --build

build-dev:
	docker compose --env-file .envs/.env.dev -f docker-compose.yml -f docker-compose.dev.yml up --build

build-test:
	docker compose --env-file .envs/.env.test -f docker-compose.yml -f docker-compose.test.yml up --build

# ==================
# TESTES
# ==================

#unit-tests
unit-test:
	./mvnw -Dtest="com/gestaosla/backend/unit/*" test

#integration-tests
integration-test:
	./mvnw -Dtest="com/gestaosla/backend/integration/*" test