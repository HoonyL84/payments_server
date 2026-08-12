FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY mock-pg-server/build.gradle.kts mock-pg-server/build.gradle.kts
RUN chmod +x gradlew && ./gradlew :mock-pg-server:dependencies --configuration runtimeClasspath --quiet --no-daemon

COPY mock-pg-server/src mock-pg-server/src
RUN ./gradlew :mock-pg-server:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/mock-pg-server/build/libs/*.jar app.jar
VOLUME /app/data
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
