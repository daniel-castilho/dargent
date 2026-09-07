# syntax=docker/dockerfile:1
# Multi-stage, non-root. Digest-pinned bases + layered-jar extraction land at M4 (release-runbook §8).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY modules modules
COPY apps apps
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre-alpine AS runtime
# E13 S2: Trivy gate caught libcrypto3 3.5.7-r0 (CVE-2026-14456, HIGH, fixed 3.5.8-r0).
# Real vulnerability = bump, not suppression: pull alpine security fixes at image build.
RUN apk upgrade --no-cache && addgroup -g 10001 dargent && adduser -u 10001 -G dargent -s /bin/false -D dargent
WORKDIR /app
COPY --from=build /workspace/apps/api/target/dargent-api.jar /app/app.jar
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=10 \
  CMD wget -qO- http://127.0.0.1:9090/actuator/health | grep -q '"status":"UP"'
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
