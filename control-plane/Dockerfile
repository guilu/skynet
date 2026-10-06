# Imagen del control plane. Contexto de build: raíz del repositorio.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY build-logic build-logic
COPY protocol protocol
COPY control-plane control-plane
COPY runner runner
COPY tools tools
COPY fixtures fixtures
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :control-plane:bootJar --no-daemon --no-configuration-cache -x test \
 && cp control-plane/build/libs/control-plane.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 skynet \
 && mkdir -p /data/artifacts && chown skynet /data/artifacts
COPY --from=build /app.jar /app/app.jar
USER skynet
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q "\"status\":\"UP\"" <&3'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
