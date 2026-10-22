# syntax=docker/dockerfile:1

# ---- build: compile + package, then split the jar into layers (dependencies change rarely -> cached) ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# The Maven repository is a BuildKit cache: downloads survive between builds (and between retries of a
# flaky network); HTTP retries cover dropped connections to Maven Central.
ENV MAVEN_ARGS="-B -q -Daether.transport.http.retryHandler.count=5"
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw dependency:go-offline
COPY src src
# tests run in CI ("./mvnw verify", needs Docker for the integration tests), not inside the image build
RUN --mount=type=cache,target=/root/.m2 ./mvnw package -DskipTests \
 && java -Djarmode=tools -jar target/payment-service-*.jar extract --layers --launcher --destination /extracted

# ---- runtime: JRE only, non-root ----
FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001

# UTC everywhere (Postgres rejects some legacy zone names); heap sized from the container memory limit;
# one JSON log line per event for the log collector.
ENV TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC" \
    LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs \
    PORT=8083

EXPOSE 8083
# Kubernetes probes: /actuator/health/liveness and /actuator/health/readiness
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
