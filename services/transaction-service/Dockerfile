# Build context: services/
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
RUN mvn install -N -q

WORKDIR /app/transaction-service
COPY transaction-service/pom.xml .
RUN mvn dependency:go-offline -q || true

COPY transaction-service/src ./src
RUN mvn clean package -DskipTests -q

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/transaction-service/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
