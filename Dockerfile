FROM adoptopenjdk:11-jre-openj9 AS base
WORKDIR /app
EXPOSE 80
EXPOSE 443

FROM maven:3.6.3-openjdk-11 AS build
WORKDIR /appsrc
COPY . .
RUN mvn package

FROM base AS final
WORKDIR /app
ARG JAR_FILE=/appsrc/target/*.jar
COPY --from=build ${JAR_FILE} app.jar
COPY wait-for-it.sh wait-for-it.sh
RUN chmod u+x wait-for-it.sh
ENTRYPOINT ["./wait-for-it.sh","mysql:3306", "-s", "-t", "60","--","java","-jar","app.jar"]