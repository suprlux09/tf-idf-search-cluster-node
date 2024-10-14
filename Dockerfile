FROM openjdk:11
ARG JAR_FILE=target/*jar-with-dependencies.jar
COPY ${JAR_FILE} app.jar
COPY .env .env
ENTRYPOINT ["java","-Xmx2000m","-jar","/app.jar"]