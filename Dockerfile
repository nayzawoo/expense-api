# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-noble@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87 AS build
# The wrapper checksum pins the ZIP distribution; prevent its tar.gz fallback.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/main/ src/main/
RUN --mount=type=cache,id=expense-api-maven,target=/root/.m2,sharing=locked \
    ./mvnw --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c AS runtime
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/expense-api-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=5s --start-period=60s --retries=12 \
    CMD curl --fail --silent --max-time 4 http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
