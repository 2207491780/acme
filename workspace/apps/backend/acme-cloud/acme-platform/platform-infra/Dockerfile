# platform-infra 镜像基线（18.1.1 第 9 步 / 15.1.1；部署资源与探针配置归后续部署特性）
FROM eclipse-temurin:25-jre-alpine
# AWT 图形渲染依赖（图形验证码 login-guard-captcha）：fontconfig + 字体，缺省即
# InternalError/字体读取失败 → SYS-00-0006。显式 headless 禁用 X11 连接。
RUN apk add --no-cache fontconfig ttf-dejavu
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"
WORKDIR /app
COPY platform-infra-service/target/platform-infra-service-*.jar app.jar
# 镜像标签随服务版本（M-13 禁 latest）；密钥经环境注入（14.3.3）
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
