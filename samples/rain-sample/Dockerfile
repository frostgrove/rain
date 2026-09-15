# syntax=docker/dockerfile:1
#
# The one image every process of the helpdesk runs: the api, the worker and the one-shot commands differ only in the
# environment they are started with (RAIN_RUNTIME_ROLES or RAIN_RUNTIME_COMMAND).
#
# Built from the bootJar, which the build makes first:
#   ./gradlew :samples:rain-sample:bootJar
#   docker compose -f samples/rain-sample/compose.yaml build

# Stage 1: the bootJar split into its layers, so a change to the application does not rebuild the dependency layers.
FROM eclipse-temurin:25-jre AS layers
WORKDIR /build
COPY build/libs/rain-sample.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# Stage 2: the runtime image.
FROM eclipse-temurin:25-jre
RUN groupadd --system rain && useradd --system --gid rain --home-dir /app rain
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER rain
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
