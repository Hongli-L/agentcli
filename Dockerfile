# 多阶段构建：开发期不依赖 Docker，仅用于发布与部署加分项。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /workspace/target/agentcli-1.0.0.jar /app/agentcli.jar
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
ENTRYPOINT ["java", "-jar", "/app/agentcli.jar"]
