FROM eclipse-temurin:21-jdk-noble@sha256:107215954359f1ab0be058a247d7c64ed6e69c71c1682d471d38ef751ec8e964 AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn ./.mvn
COPY common-security/pom.xml ./common-security/pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml
COPY product-service/pom.xml ./product-service/pom.xml
COPY inventory-service/pom.xml ./inventory-service/pom.xml
COPY order-service/pom.xml ./order-service/pom.xml
COPY notification-service/pom.xml ./notification-service/pom.xml
RUN chmod +x mvnw && ./mvnw -B -pl notification-service -am dependency:go-offline
COPY notification-service/src ./notification-service/src
RUN ./mvnw -B -pl notification-service -am package -DskipTests \
    && cp notification-service/target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=10001:10001 /app/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /app/extracted/application/ ./
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "application.jar"]
