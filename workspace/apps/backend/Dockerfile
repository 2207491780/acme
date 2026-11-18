# acme-cloud 服务镜像 —— 构建上下文：workspace/apps/backend/（含 acme-parent 兄弟目录，
# acme-cloud 的 parent 由 relativePath ../acme-parent 解析，上下文缺它则父 POM 不可解析）
# 用法（MODULE_PATH 为相对 acme-cloud/ 的 reactor 模块路径；PACKAGE 为打包形态标记）：
#   cd workspace/apps/backend
#   docker build --build-arg PACKAGE=module --build-arg MODULE_PATH=acme-gateway \
#     -t $REGISTRY/acme/gateway:<tag> .
#   docker build --build-arg PACKAGE=module --build-arg MODULE_PATH=acme-platform/platform-auth/platform-auth-service \
#     -t $REGISTRY/acme/platform-auth:<tag> .
# 单体形态（REQ-20261118-002130 确认⑥，实现取简）：PACKAGE=bootstrap 仅为形态标记——
# 单体构建等价于 MODULE_PATH=acme-bootstrap 的普通模块构建，cd.yml 的 PACKAGE_MODE 输入
# 决定构建哪个模块与镜像名（acme/acme-monolith:<tag>）：
#   docker build --build-arg PACKAGE=bootstrap --build-arg MODULE_PATH=acme-bootstrap \
#     -t $REGISTRY/acme/monolith:<tag> .
# 运行配置全部经环境变量注入（NACOS_ADDR/PG_*/REDIS_* 等），见 env-stack/k8s/base；
# 单体运行面差异（无 Nacos/Kafka 软依赖、三 Flyway 手动实例）见 acme-bootstrap 的 application.yml。
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY acme-parent acme-parent
COPY acme-cloud acme-cloud

ARG PACKAGE=module
# 打包形态标记（module=分布式服务；bootstrap=单体聚合）；真正的构建对象由 MODULE_PATH 决定
ARG MODULE_PATH

# acme-parent 用 flatten+${revision} 版本，而 relativePath 父解析不插值候选 POM 的版本字面量，
# 必须先把父 POM install 进本地仓库（flatten 展开为 1.0-SNAPSHOT 字面量），acme-cloud 才能解析
# .m2 用 BuildKit 缓存挂载：同上下文连建多个服务镜像时共享依赖，只首建全量下载。
# 可执行 jar 取用：三服务经 -exec 分类器附加（主构件为普通 jar 供单体聚合依赖，REQ-20261118-002130），
# 故优先取 *-exec.jar；无 -exec 产物的模块（gateway/acme-bootstrap，主构件即可执行 jar）回退取唯一 jar。
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -f acme-parent/pom.xml install \
 && mvn -B -q -DskipTests -f acme-cloud/pom.xml -pl "$MODULE_PATH" -am package \
 && j="acme-cloud/$MODULE_PATH/target" \
 && { f="$(ls "$j"/*-exec.jar 2>/dev/null | head -1)"; \
      [ -n "$f" ] || f="$(ls "$j"/*.jar | grep -v '\.original$' | head -1)"; \
      cp "$f" /app.jar; }

FROM eclipse-temurin:25-jre-noble
COPY --from=build /app.jar /app/app.jar
# 非root运行；JVM 堆按容器限额自适应（deployment 注入 JAVA_TOOL_OPTIONS 调 MaxRAMPercentage）
USER 10001
WORKDIR /app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
