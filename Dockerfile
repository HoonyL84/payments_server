FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY mock-pg-server/build.gradle.kts mock-pg-server/build.gradle.kts
COPY payment-event-consumer/build.gradle.kts payment-event-consumer/build.gradle.kts
RUN chmod +x gradlew && ./gradlew dependencies --configuration runtimeClasspath --quiet --no-daemon

COPY src ./src
RUN ./gradlew :bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
