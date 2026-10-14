# Build context: services/
FROM maven:3.9-eclipse-temurin-17 AS build

# 1. Install the parent POM into the local Maven repo (-N = this POM only, skip the child modules)
WORKDIR /app
COPY pom.xml .
RUN mvn install -N -q

# 2. Resolve the api-gateway dependencies (cached until the pom changes)
WORKDIR /app/api-gateway
COPY api-gateway/pom.xml .
RUN mvn dependency:go-offline -q || true

# 3. Copy the source code and build
COPY api-gateway/src ./src
RUN mvn clean package -DskipTests -q

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/api-gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
