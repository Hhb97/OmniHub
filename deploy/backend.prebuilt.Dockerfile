# Build context: repository root. Uses the jar built on the workstation (deploy-vm.ps1 -Prebuilt) instead of building in Docker.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 iot
WORKDIR /app
COPY deploy/prebuilt/app.jar app.jar
USER iot
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
