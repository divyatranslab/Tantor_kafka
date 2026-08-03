# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1500 --create-home tantor
COPY --from=build /src/target/tantor-server-*.jar /app/app.jar

# Compile the custom DNS provider into a separate JAR
COPY src/main/java/io/translab/tantor/server/config/FastReverseDnsProvider.java /tmp/FastReverseDnsProvider.java
COPY src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider /tmp/META-INF/services/java.net.spi.InetAddressResolverProvider
RUN javac -d /tmp/classes /tmp/FastReverseDnsProvider.java && \
    cp -r /tmp/META-INF /tmp/classes/ && \
    jar cf /app/fast-dns-provider.jar -C /tmp/classes . && \
    rm -rf /tmp/classes /tmp/FastReverseDnsProvider.java /tmp/META-INF

USER tantor
EXPOSE 8443
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp \"/app/fast-dns-provider.jar:/app/app.jar\" org.springframework.boot.loader.launch.JarLauncher"]
