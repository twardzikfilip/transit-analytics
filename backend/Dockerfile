# transit-backend image, from the boot jar: (cd backend && ./gradlew :bootJar) && docker build backend
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/config/map && chown -R 1000:1000 /app/config
WORKDIR /app
COPY build/libs/transit-backend.jar app.jar
USER 1000:1000
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=3 \
  CMD curl -fs http://localhost:8080/actuator/health > /dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
