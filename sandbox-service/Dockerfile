FROM eclipse-temurin:21-jre

WORKDIR /app
COPY target/sandbox-service-0.0.1-SNAPSHOT.jar /app/sandbox-service.jar

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/sandbox-service.jar"]
