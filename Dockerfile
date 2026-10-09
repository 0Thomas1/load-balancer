# syntax=docker/dockerfile:1

FROM node:22-alpine AS frontend-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /build/backend
COPY backend/pom.xml ./
COPY backend/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /opt/app
COPY --from=backend-build /build/backend/target/*.jar app.jar
COPY --from=frontend-build /build/frontend/dist ./frontend/dist
RUN addgroup -S appgroup && adduser -S appuser -G appgroup && chown -R appuser:appgroup /opt/app
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--app.frontend-dist=/opt/app/frontend/dist"]
