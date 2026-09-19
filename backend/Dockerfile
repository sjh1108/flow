# syntax=docker/dockerfile:1
#
# NOTE FOR OCI: Ampere A1 instances are ARM64. Building this image on an x86
# laptop and pushing it to an A1 instance produces an image that will not run.
# Either build on the instance itself (simplest) or use `docker buildx build
# --platform linux/arm64`. Both base images below are multi-arch.

# ---------- build ----------
FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace

# Copy build definitions first so dependency resolution is cached independently
# of source changes.
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle --no-daemon dependencies --quiet || true

COPY src ./src
RUN gradle --no-daemon bootJar --quiet

# ---------- run ----------
FROM eclipse-temurin:21-jre AS runtime

# Unprivileged user: nothing here needs root, and uploads must not be written as root.
RUN groupadd --system --gid 1001 extguard \
 && useradd --system --uid 1001 --gid extguard --home /app extguard \
 && mkdir -p /app /var/lib/extguard/files \
 && chown -R extguard:extguard /app /var/lib/extguard

WORKDIR /app
COPY --from=build --chown=extguard:extguard /workspace/build/libs/*.jar app.jar

USER extguard

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"
ENV STORAGE_ROOT=/var/lib/extguard/files
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
