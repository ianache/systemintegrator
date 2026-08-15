FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml .
COPY application/pom.xml application/pom.xml
COPY e2e/pom.xml e2e/pom.xml
RUN mvn -B -pl application -am dependency:go-offline -DskipTests
COPY src src
RUN mvn -B -pl application -am package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/application/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
