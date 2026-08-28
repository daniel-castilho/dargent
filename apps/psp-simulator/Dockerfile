# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY modules modules
COPY apps apps
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -g 10001 dargent && adduser -u 10001 -G dargent -s /bin/false -D dargent
WORKDIR /app
COPY --from=build /workspace/apps/psp-simulator/target/dargent-psp-simulator.jar /app/app.jar
USER 10001
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8090/actuator/health | grep -q '"status":"UP"'
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
