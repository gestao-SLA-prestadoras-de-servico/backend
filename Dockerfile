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

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

CMD ["java", "-jar", "app.jar"]