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