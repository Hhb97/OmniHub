# Build context: repository root. Builds on JDK 21 without the embedded PostgreSQL (development only).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -B -q -P '!embedded' -Djava.version=21 dependency:go-offline
COPY backend/src ./src
RUN mvn -B -q -P '!embedded' -Djava.version=21 -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 iot && apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/iot-backend-*.jar app.jar
USER iot
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
