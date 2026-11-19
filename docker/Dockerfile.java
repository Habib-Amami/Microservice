# syntax=docker/dockerfile:1
# Multistage build for one Spring Boot application at a time. Compose passes
# MODULE (e.g. services/product-service or infrastructure/api-gateway).
#
# Optimisations:
#   1. Only the selected module's POM + sources are copied, so a change in the
#      frontend or another service never invalidates this build cache.
#   2. Maven dependencies live in a shared BuildKit cache mount across all
#      nine images.
#   3. The repackaged jar is exploded into Spring Boot layers; the dependency
#      layers are copied before the application layer so a code change only
#      invalidates a few KB.
#   4. The runtime is an Alpine JRE with no build tools or source code in the
#      final image.
FROM maven:3.9-eclipse-temurin-21 AS builder
ARG MODULE
WORKDIR /workspace

# POMs first: infrastructure modules inherit infrastructure/pom.xml, the six
# services inherit spring-boot-starter-parent from Maven Central.
COPY infrastructure/pom.xml ./infrastructure/pom.xml
COPY ${MODULE}/pom.xml ./${MODULE}/pom.xml
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -f ${MODULE}/pom.xml -Dmaven.test.skip=true validate

COPY ${MODULE}/src ./${MODULE}/src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -f ${MODULE}/pom.xml -Dmaven.test.skip=true package \
 && java -Djarmode=layertools -jar ${MODULE}/target/*.jar extract --destination /extracted

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
# Order matters for layer caching: dependencies change rarely, application
# code changes often.
COPY --from=builder /extracted/dependencies/ ./
COPY --from=builder /extracted/spring-boot-loader/ ./
COPY --from=builder /extracted/snapshot-dependencies/ ./
COPY --from=builder /extracted/application/ ./
EXPOSE 8080 8082 8083 8084 8085 8086 8087 8761 8888
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
