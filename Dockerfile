# Multi-stage build. Not executed on the authoring machine (Docker was not installed) - review before relying on it.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY .mvn .mvn
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 appuser && mkdir -p /app/data /app/workspace && chown -R appuser /app
COPY --from=build /src/target/agentic-engineering-platform-1.0.0.jar app.jar
USER appuser
EXPOSE 8080
ENV JAVA_OPTS="-Xmx384m -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]