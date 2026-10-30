# Build context: repository root. Builds on JDK 21 without the embedded PostgreSQL (development only).
#
# The backend is a multi-module Maven build: the platform-* modules hold the code, only `core` produces the
# executable jar. The poms are copied first so that a source change does not invalidate the dependency layer.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY backend/pom.xml ./pom.xml
COPY backend/platform-common/pom.xml ./platform-common/pom.xml
COPY backend/platform-messaging/pom.xml ./platform-messaging/pom.xml
COPY backend/platform-identity/pom.xml ./platform-identity/pom.xml
COPY backend/platform-product/pom.xml ./platform-product/pom.xml
COPY backend/platform-device/pom.xml ./platform-device/pom.xml
COPY backend/platform-telemetry/pom.xml ./platform-telemetry/pom.xml
COPY backend/platform-command/pom.xml ./platform-command/pom.xml
COPY backend/platform-alarm/pom.xml ./platform-alarm/pom.xml
COPY backend/platform-realtime/pom.xml ./platform-realtime/pom.xml
COPY backend/platform-ingest/pom.xml ./platform-ingest/pom.xml
COPY backend/platform-dashboard/pom.xml ./platform-dashboard/pom.xml
COPY backend/core/pom.xml ./core/pom.xml
# excludeGroupIds: the sibling modules are built in the same reactor, so they cannot be resolved yet
RUN mvn -B -q -P '!embedded' -Djava.version=21 -DexcludeGroupIds=com.omnihub dependency:go-offline

COPY backend ./
RUN mvn -B -q -P '!embedded' -Djava.version=21 -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 iot && apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/core/target/iot-backend-*.jar app.jar
USER iot
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
