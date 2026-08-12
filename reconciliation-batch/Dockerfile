FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY mock-pg-server/build.gradle.kts mock-pg-server/build.gradle.kts
COPY payment-event-consumer/build.gradle.kts payment-event-consumer/build.gradle.kts
COPY reconciliation-batch/build.gradle.kts reconciliation-batch/build.gradle.kts
RUN chmod +x gradlew && ./gradlew :reconciliation-batch:dependencies --configuration runtimeClasspath --quiet --no-daemon
COPY reconciliation-batch/src reconciliation-batch/src
COPY src/main/resources/db/migration src/main/resources/db/migration
RUN ./gradlew :reconciliation-batch:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/reconciliation-batch/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]