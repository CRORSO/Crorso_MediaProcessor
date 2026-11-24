# syntax=docker/dockerfile:1

# ---- Build stage: compile the Spring Boot jar with the Gradle wrapper (JDK 21) ----
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace

# Copy build descriptors first so dependency resolution is cached between source changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY backend/build.gradle backend/
RUN chmod +x gradlew && ./gradlew --no-daemon :backend:dependencies -q > /dev/null 2>&1 || true

COPY backend/src backend/src
# Tests need FFmpeg, which is not installed in this stage; run them locally or in CI (see document/LOCAL_DEVELOPMENT.md).
RUN ./gradlew --no-daemon :backend:bootJar -x test

# ---- Runtime stage: JRE 21 + FFmpeg/FFprobe, non-root, no secrets ----
FROM eclipse-temurin:21-jre-noble AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system media \
    && useradd --system --gid media --home-dir /app --shell /usr/sbin/nologin media \
    && mkdir -p /app /data/temp /data/output \
    && chown -R media:media /app /data

WORKDIR /app
COPY --from=build --chown=media:media /workspace/backend/build/libs/crorso-media-processor.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=prod \
    MEDIA_TEMP_DIR=/data/temp \
    MEDIA_OUTPUT_DIR=/data/output \
    FFMPEG_PATH=ffmpeg \
    FFPROBE_PATH=ffprobe \
    JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"

USER media
EXPOSE 8080

# Railway sets PORT and checks /actuator/health itself; this keeps local `docker run` and Compose honest too.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT:-8080}/actuator/health" > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
