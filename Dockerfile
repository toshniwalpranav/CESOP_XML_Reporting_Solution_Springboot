FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/cesop-api-1.0.0.jar app.jar
ENV SPRING_PROFILES_ACTIVE=public
ENV JAVA_TOOL_OPTIONS="-Xmx384m"
EXPOSE 8080
USER 1000
ENTRYPOINT ["java","-jar","app.jar"]
