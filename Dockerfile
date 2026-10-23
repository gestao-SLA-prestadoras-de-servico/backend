# ====== BASE ======

FROM eclipse-temurin:21-jdk AS base

WORKDIR /app

COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw .

RUN ./mvnw dependency:go-offline

COPY src src

# ====== DEVELOPMENT ENVIRONMENT ======

FROM base as development

CMD ["./mvnw", "spring-boot:run"]

# ====== TEST ENVIRONMENT ======

FROM base as test

CMD ["./mvnw", "spring-boot:run"]

# ====== PREPARE TO PRODUCTION ======

FROM base AS build

RUN ./mvnw clean package -DskipTests

# ====== PRODUCTION ENVIRONMENT ======

FROM eclipse-temurin:21-jre AS production

# A imagem não traz curl nem wget; o curl é usado pelo HEALTHCHECK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --no-create-home app

WORKDIR /app

COPY --from=build --chown=app:app /app/target/*.jar app.jar

USER app

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fs http://localhost:8080/actuator/health || exit 1

CMD ["java", "-jar", "app.jar"]