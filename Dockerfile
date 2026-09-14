FROM eclipse-temurin:21-jdk AS base

WORKDIR /app

COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw .

RUN ./mvnw dependency:go-offline

COPY src src

FROM base as development

CMD ["./mvnw", "spring-boot:run"]

FROM base as test

CMD ["./mvnw", "spring-boot:run"]