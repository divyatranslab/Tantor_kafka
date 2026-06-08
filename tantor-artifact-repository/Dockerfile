# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
# Pre-fetch dependencies so they cache independently of source changes.
# In an air-gapped build, seed a local mirror instead of going offline here.
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Non-root runtime user; matches the 'tantor' convention used by the agent.
RUN useradd --system --uid 1500 --create-home tantor
COPY --from=build /src/target/tantor-artifact-repository-*.jar /app/app.jar

# Repository data lives on a mounted volume (PVC in k8s).
RUN mkdir -p /var/lib/tantor/repository && chown -R tantor:tantor /var/lib/tantor
VOLUME ["/var/lib/tantor/repository"]

USER tantor
EXPOSE 8081
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
