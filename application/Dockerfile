FROM openjdk:11.0.13-jre-slim-buster
COPY target/app.jar app.jar
RUN mkdir -p file_repo/knowledge/archetypes &&  mkdir -p file_repo/knowledge/operational_templates && mkdir -p file_repo/knowledge/templates
EXPOSE 8080
ENTRYPOINT ["java","-Dspring.profiles.active=docker" ,"-jar","/app.jar"]
