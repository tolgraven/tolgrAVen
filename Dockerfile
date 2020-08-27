FROM openjdk:8-alpine

COPY target/uberjar/tolgraven.jar /tolgraven/app.jar

EXPOSE 3000

CMD ["java", "-jar", "/tolgraven/app.jar"]
