# One Dockerfile for all three services: docker build --build-arg SERVICE=payments-service -f infra/docker/Dockerfile .
# Stage 1 builds the executable jar; stage 2 is a minimal non-root runtime.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ARG SERVICE
RUN ./gradlew --no-daemon :services:${SERVICE}:bootJar -x test \
 && mkdir -p /out && cp services/${SERVICE}/build/libs/${SERVICE}-*-SNAPSHOT.jar /out/app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=app:app /out/app.jar /app/app.jar
USER 10001:10001
# Container-aware heap sizing; JVM exits on OOM so the orchestrator restarts a clean process.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
