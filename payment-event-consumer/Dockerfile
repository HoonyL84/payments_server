FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY mock-pg-server/build.gradle.kts mock-pg-server/build.gradle.kts
COPY payment-event-consumer/build.gradle.kts payment-event-consumer/build.gradle.kts
RUN chmod +x gradlew && ./gradlew :payment-event-consumer:dependencies --configuration runtimeClasspath --quiet --no-daemon

COPY payment-event-consumer/src payment-event-consumer/src
RUN ./gradlew :payment-event-consumer:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/payment-event-consumer/build/libs/*.jar app.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
