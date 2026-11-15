# Runtime image for one module. Build the jars on the host first:
#   mvn -B -DskipTests package
# Then: docker build --build-arg MODULE=rl-gateway -t rl-loadtest/rl-gateway .
FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-*-SNAPSHOT.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
