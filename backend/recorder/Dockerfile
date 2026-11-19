# transit-recorder image. Built by docker compose (see docker-compose.yml) from the boot jar:
#   (cd backend && ./gradlew :recorder:bootJar) && docker compose build recorder
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data/raw /data/spool
WORKDIR /app
COPY build/libs/transit-recorder.jar app.jar
ENV RECORDING_ARCHIVE_DIR=/data/raw RECORDING_SPOOL_DIR=/data/spool
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fs http://localhost:8081/actuator/health > /dev/null || exit 1
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
