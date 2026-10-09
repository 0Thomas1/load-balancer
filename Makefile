.PHONY: dev-backend dev-frontend test-backend test-frontend build-frontend

## Backend (Spring Boot, port 8080)
dev-backend:
	cd backend && ./mvnw spring-boot:run

dev-frontend:
	cd frontend && npm run dev

test-backend:
	cd backend && ./mvnw test

test-frontend:
	cd frontend && npm test

build-frontend:
	cd frontend && npm run build

## Run the whole sandbox as one Docker container
.PHONY: docker-build docker-up docker-down
docker-build:
	docker build --platform linux/amd64 -t load-balancer-sandbox:local .

docker-up:
	docker compose up -d --build

docker-down:
	docker compose down