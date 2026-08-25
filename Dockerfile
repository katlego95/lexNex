# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage. A full JDK plus Maven, none of which belongs in the shipped image.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Dependencies resolve in their own layer, keyed only on the pom. Editing a Java file then costs
# a recompile; editing the pom is what costs a re-download.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are not run here on purpose: the image build is packaging, and CI is where a failing test
# should stop the pipeline. Building an image from unverified source is the thing to avoid, and
# that is enforced by the order of the CI steps, not by repeating the suite in every docker build.
RUN mvn -B -q package -DskipTests

# ---------------------------------------------------------------------------
# Runtime stage. A JRE, the jar, and nothing else — no compiler, no Maven, no source.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# Non-root. A service that parses attacker-supplied XML should not be running as uid 0; if a parser
# vulnerability ever gets code execution, this is the difference between a contained blast radius
# and a host compromise.
RUN groupadd --system --gid 1001 lexpipeline \
    && useradd --system --uid 1001 --gid lexpipeline --home /app --shell /usr/sbin/nologin lexpipeline

WORKDIR /app
COPY --from=build /build/target/lexpipeline-*.jar /app/lexpipeline.jar

# The mount points. Declared as volumes so a `docker run -v` has an obvious target and so the
# container writes artifacts to a bind mount rather than into its own writable layer.
RUN mkdir -p /data/in /data/out && chown -R lexpipeline:lexpipeline /app /data
VOLUME ["/data/in", "/data/out"]

# Every setting is an environment variable, so one image serves every environment. These are the
# container-appropriate defaults; the application's own defaults suit a developer's laptop.
ENV APP_INPUT_DIR=/data/in \
    APP_OUTPUT_DIR=/data/out \
    APP_CONCURRENCY=4 \
    APP_QUEUE_CAPACITY=64 \
    APP_MAX_DOC_BYTES=10485760 \
    SERVER_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

USER lexpipeline
EXPOSE 8080

# Readiness, not liveness: this reports whether the schema and stylesheets are compiled, which is
# what "can this instance accept a judgment" means.
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD ["sh", "-c", "curl -fsS http://localhost:${SERVER_PORT}/actuator/health/readiness || exit 1"]

# exec form via sh so JAVA_OPTS is expanded; the JVM stays PID 1 so it receives SIGTERM and the
# worker pool gets its graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/lexpipeline.jar"]
