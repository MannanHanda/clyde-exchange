# Builds one image that contains the whole exchange: the server, the bots and the dashboard.
# Three stages, so the final image holds only a Java runtime and one jar (no Node, no Maven, no source code).

# 1. Build the dashboard (React + Perspective) into static files
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# 2. Build the server jar, with the dashboard inside it.
#    Spring Boot serves anything in resources/static, so the jar answers both / and /api.
#    Tests are skipped here: CI runs them, and an image build should not need a second database.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY engine/ engine/
COPY server/ server/
COPY --from=web /web/dist/ server/src/main/resources/static/
RUN mvn -B -DskipTests package

# 3. The image that actually runs
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --create-home clyde && mkdir /app/data && chown clyde /app/data
USER clyde
COPY --from=build /src/server/target/server-0.1.0-SNAPSHOT.jar app.jar

# Without a PostgreSQL address the exchange saves to an H2 file in /app/data: mount a volume there to keep it
VOLUME /app/data
EXPOSE 8080

# Let Java use three quarters of the container's memory, and leave the rest for the operating system
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# Docker marks the container unhealthy if the server or its database connection stops answering
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
