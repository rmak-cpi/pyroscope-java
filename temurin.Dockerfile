ARG JAVA_VERSION
FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine as build
WORKDIR /app
ADD gradlew build.gradle settings.gradle /app/
ADD gradle gradle
RUN ./gradlew --no-daemon --version
ADD agent agent
ADD async-profiler-context async-profiler-context
ADD demo/build.gradle demo/

RUN ./gradlew --no-daemon shadowJar

ADD demo demo

RUN javac demo/src/main/java/Fib.java
ARG JAVA_VERSION
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine
ARG JAVA_VERSION

WORKDIR /app

COPY --from=build /app/agent/build/libs/pyroscope.jar /app/agent/build/libs/pyroscope.jar
COPY --from=build /app/demo/src/main/java/ /app/demo/src/main/java/

ENV PYROSCOPE_LOG_LEVEL=debug
ENV PYROSCOPE_SERVER_ADDRESS=http://pyroscope:4040
ENV PYROSCOPE_APPLICATION_NAME=temurin-${JAVA_VERSION}-alpine
ENV PYROSCOPE_UPLOAD_INTERVAL=15s
CMD ["java", "-javaagent:/app/agent/build/libs/pyroscope.jar", "-cp", "demo/src/main/java/", "Fib"]
