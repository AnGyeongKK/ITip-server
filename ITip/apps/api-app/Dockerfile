ARG JAVA_IMAGE=eclipse-temurin:21-jre
FROM ${JAVA_IMAGE}
WORKDIR /app
ARG JAR_FILE=apps/api-app/build/libs/api-app-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
