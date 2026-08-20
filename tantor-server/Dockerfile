# syntax=docker/dockerfile:1
FROM maven:3.9.16-eclipse-temurin-21-noble@sha256:c07f7ccfb8ca6c9fa29ee523f00afa7d2ca6132c92f8652c4aebb5ee3491f502 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests \
    && cp "$(find target -maxdepth 1 -type f -name 'tantor-server-*.jar' ! -name '*-dns-resolver.jar' -print -quit)" target/app.jar \
    && cp "$(find target -maxdepth 1 -type f -name 'tantor-server-*-dns-resolver.jar' -print -quit)" target/dns-resolver.jar

FROM eclipse-temurin:21.0.11_10-jre-resolute@sha256:b3598c584b6f797ee2324baac2ae5ceb6c8fb3a97f3b11e5a6ee4fd47308bc58
WORKDIR /app
RUN useradd --system --uid 1500 --create-home tantor \
    && mkdir -p /srv/tantor/security/truststores \
    && chown -R tantor:tantor /srv/tantor
COPY --from=build /src/target/app.jar /app/app.jar
COPY --from=build /src/target/dns-resolver.jar /app/dns-resolver.jar
USER tantor
EXPOSE 8443
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp /app/dns-resolver.jar:/app/app.jar org.springframework.boot.loader.launch.JarLauncher"]
