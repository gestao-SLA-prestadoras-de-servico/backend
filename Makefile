run-dev:
	docker compose --env-file .envs/.env.dev -f docker-compose.yml -f docker-compose.dev.yml up

run-test:
	docker compose --env-file .envs/.env.test -f docker-compose.yml -f docker-compose.test.yml up