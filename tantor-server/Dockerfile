# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests \
    && cp "$(find target -maxdepth 1 -type f -name 'tantor-server-*.jar' ! -name '*-dns-resolver.jar' -print -quit)" target/app.jar \
    && cp "$(find target -maxdepth 1 -type f -name 'tantor-server-*-dns-resolver.jar' -print -quit)" target/dns-resolver.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1500 --create-home tantor
COPY --from=build /src/target/app.jar /app/app.jar
COPY --from=build /src/target/dns-resolver.jar /app/dns-resolver.jar
USER tantor
EXPOSE 8443
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp /app/dns-resolver.jar:/app/app.jar org.springframework.boot.loader.launch.JarLauncher"]
