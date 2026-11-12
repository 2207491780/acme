# platform-system 镜像基线（18.1.1 第 9 步 / 15.1.1；部署资源与探针配置归后续部署特性）
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY platform-system-service/target/platform-system-service-*.jar app.jar
# 镜像标签随服务版本（M-13 禁 latest）；密钥经环境注入（14.3.3）
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
