# syntax=docker/dockerfile:1
#
# Multi-stage. Four stages so that the slow, cacheable work (dependency
# resolution) is separated from the work that changes every commit (compiling
# src/), and so the runtime image carries a JRE rather than a JDK plus Maven.

# ---------------------------------------------------------------- build -----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first: this layer is rebuilt only when pom.xml changes, so an
# ordinary source edit does not re-download the world.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q clean package -DskipTests

# ------------------------------------------------------------- unpack -------
# Exploding the fat jar lets the JVM load classes from a plain directory, which
# is what makes the CDS archive in the next stage actually usable at runtime.
FROM eclipse-temurin:21-jre-alpine AS unpack
WORKDIR /stage
COPY --from=build /build/target/app.jar app.jar
# Produces extracted/app.jar (thin launcher) alongside extracted/lib/*.jar
RUN java -Djarmode=tools -jar app.jar extract --destination extracted \
 && rm app.jar \
 && mv extracted/* . \
 && rmdir extracted

# ---------------------------------------------------------------- cds -------
# Class Data Sharing training run: start the context once, dump the loaded
# classes, exit. Cuts JVM startup roughly in half, which matters on a free-tier
# instance that cold-starts.
#
# The training run must not touch a database, so Flyway and the DB health check
# are disabled and Hikari is told not to fail when it cannot connect. If the run
# fails anyway the build continues without an archive - at runtime -Xshare:auto
# silently ignores a missing or stale archive, so a failed training run costs
# startup time and nothing else.
FROM eclipse-temurin:21-jre-alpine AS cds
WORKDIR /stage
COPY --from=unpack /stage/ ./
RUN java -XX:ArchiveClassesAtExit=app.jsa \
         -Dspring.context.exit=onRefresh \
         -Dspring.flyway.enabled=false \
         -Dspring.datasource.hikari.initialization-fail-timeout=-1 \
         -Dmanagement.health.db.enabled=false \
         -jar app.jar \
    || echo "CDS training run failed - continuing without an archive"

# ------------------------------------------------------------- runtime ------
FROM eclipse-temurin:21-jre-alpine

# curl is needed by HEALTHCHECK below and nothing else.
RUN apk add --no-cache curl

# Non-root, with a fixed uid so a bind-mounted volume has predictable ownership.
RUN addgroup -S -g 10001 wallet \
 && adduser  -S -u 10001 -G wallet -h /app -s /sbin/nologin wallet

WORKDIR /app
COPY --from=cds --chown=10001:10001 /stage/ ./

USER 10001:10001
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the free tiers this deploys to
# hand out 512MB, and the JVM should size itself from the cgroup limit rather
# than from a number baked into the image.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:SharedArchiveFile=/app/app.jsa -Xshare:auto"

# Uses the readiness probe, which reports DOWN while Postgres is unreachable -
# so an instance that cannot reach its database is correctly reported unhealthy
# rather than merely "process is alive".
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/health/readiness || exit 1

# Exec form: the JVM becomes PID 1 and receives SIGTERM directly, which is what
# makes server.shutdown=graceful actually drain in-flight transfers.
ENTRYPOINT ["java", "-jar", "app.jar"]
