# Build context: services/
FROM maven:3.9-eclipse-temurin-17 AS build

# 1. Parent POM'u local Maven repo'ya kur (-N = sadece bu POM, child modüllere bakma)
WORKDIR /app
COPY pom.xml .
RUN mvn install -N -q

# 2. api-gateway bağımlılıklarını çöz (pom değişmedikçe cache'den gelir)
WORKDIR /app/api-gateway
COPY api-gateway/pom.xml .
RUN mvn dependency:go-offline -q || true

# 3. Kaynak kodu kopyala ve build et
COPY api-gateway/src ./src
RUN mvn clean package -DskipTests -q

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/api-gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
