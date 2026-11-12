# acme-cloud 服务镜像 —— 构建上下文：workspace/apps/backend/（含 acme-parent 兄弟目录，
# acme-cloud 的 parent 由 relativePath ../acme-parent 解析，上下文缺它则父 POM 不可解析）
# 用法（MODULE_PATH 为相对 acme-cloud/ 的 reactor 模块路径）：
#   cd workspace/apps/backend
#   docker build --build-arg MODULE_PATH=acme-gateway \
#     -t $REGISTRY/acme/gateway:<tag> .
#   docker build --build-arg MODULE_PATH=acme-platform/platform-auth/platform-auth-service \
#     -t $REGISTRY/acme/platform-auth:<tag> .
# 运行配置全部经环境变量注入（NACOS_ADDR/PG_*/REDIS_* 等），见 env-stack/k8s/base。
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY acme-parent acme-parent
COPY acme-cloud acme-cloud

ARG MODULE_PATH
# acme-parent 用 flatten+${revision} 版本，而 relativePath 父解析不插值候选 POM 的版本字面量，
# 必须先把父 POM install 进本地仓库（flatten 展开为 1.0-SNAPSHOT 字面量），acme-cloud 才能解析
# .m2 用 BuildKit 缓存挂载：同上下文连建多个服务镜像时共享依赖，只首建全量下载
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -f acme-parent/pom.xml install \
 && mvn -B -q -DskipTests -f acme-cloud/pom.xml -pl "$MODULE_PATH" -am package \
 && cp "$(ls "acme-cloud/$MODULE_PATH"/target/*.jar | grep -v '\.original$' | head -1)" /app.jar

FROM eclipse-temurin:25-jre-noble
COPY --from=build /app.jar /app/app.jar
# 非root运行；JVM 堆按容器限额自适应（deployment 注入 JAVA_TOOL_OPTIONS 调 MaxRAMPercentage）
USER 10001
WORKDIR /app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
