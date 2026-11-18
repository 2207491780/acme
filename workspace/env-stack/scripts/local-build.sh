#!/bin/sh
# 用法：local-build.sh —— 本机构建产物（ADR 2026-11-18-03：CI/CD 本地化；构建与部署以产物目录为接口，
# build 零凭证、deploy 凭证/网络分离，两步可分别执行）。
# 产物目录：.ai-tmp/build/<env>-<sha7>/（gitignore 不入库；ENV-STACK 约定构建产物放根 .ai-tmp/）
#   backend/       四个可执行 jar：gateway.jar / platform-auth.jar / platform-infra.jar / platform-system.jar
#   frontend/web/  dist + nginx.conf
#   images/        五个镜像 tar（docker save；镜像名 acme/<名>:<tag> 无 registry 前缀——前缀由 deploy 侧 docker tag）
#   meta.env       TAG/SHA7/ENV/CREATED —— deploy 的唯一输入接口
# 前置（本机，仓库根执行）：Docker；Maven（不在 PATH 时 export MVN_CMD=完整路径，唯一口径见
#   .ai-memory/playbooks/flex-test-assembly.md「构建与运行」）；Node≥22.13 + pnpm11.8。
# 工具链以本机为准（JDK25，与镜像内 temurin-25 同源）；本地打包 skipTests，测试门禁由 ci.yml 承担。
set -eu

REPO_ROOT=$(git rev-parse --show-toplevel)
SHA7=$(git rev-parse --short=7 HEAD)
TAG="prod-$SHA7"
ART="$REPO_ROOT/.ai-tmp/build/$TAG"

echo "▶ 产物目录：$ART"
rm -rf "$ART"
mkdir -p "$ART/backend" "$ART/frontend/web" "$ART/images"

echo "▶ [backend] Maven：先 install acme-parent（flatten+revision 需父 POM 入库，同自包含 Dockerfile 口径）"
(cd "$REPO_ROOT/workspace/apps/backend/acme-cloud" \
 && "${MVN_CMD:-mvn}" -B -q -f ../acme-parent/pom.xml install \
 && "${MVN_CMD:-mvn}" -B -q -DskipTests \
      -pl acme-gateway,acme-platform/platform-auth/platform-auth-service,acme-platform/platform-infra/platform-infra-service,acme-platform/platform-system/platform-system-service \
      -am package)
B="$REPO_ROOT/workspace/apps/backend/acme-cloud"
# jar 取用口径同自包含 Dockerfile：三服务取 *-exec.jar；gateway 主构件即可执行 jar（剔 .original）
cp "$(ls "$B/acme-gateway"/target/*.jar | grep -v '\.original$' | head -1)" "$ART/backend/gateway.jar"
cp "$(ls "$B/acme-platform/platform-auth/platform-auth-service"/target/*-exec.jar | head -1)" "$ART/backend/platform-auth.jar"
cp "$(ls "$B/acme-platform/platform-infra/platform-infra-service"/target/*-exec.jar | head -1)" "$ART/backend/platform-infra.jar"
cp "$(ls "$B/acme-platform/platform-system/platform-system-service"/target/*-exec.jar | head -1)" "$ART/backend/platform-system.jar"
echo "✅ [backend] 四 jar 落盘"

echo "▶ [frontend] pnpm 构建 @acme/web（命令口径同自包含 Dockerfile.web）"
(cd "$REPO_ROOT/workspace/apps/frontend" \
 && pnpm install --frozen-lockfile --filter @acme/web... \
 && pnpm --filter @acme/web build)
cp -r "$REPO_ROOT/workspace/apps/frontend/web/dist" "$ART/frontend/web/dist"
cp "$REPO_ROOT/workspace/apps/frontend/web/nginx.conf" "$ART/frontend/web/nginx.conf"
echo "✅ [frontend] dist 落盘"

echo "▶ [images] 五镜像构建（消费产物，轻量 prebuilt Dockerfile，产物 staging 为独立上下文）"
for s in gateway platform-auth platform-infra platform-system; do
  ctx="$ART/.ctx/$s"; mkdir -p "$ctx"
  cp "$REPO_ROOT/workspace/apps/backend/Dockerfile.prebuilt" "$ctx/Dockerfile"
  cp "$ART/backend/$s.jar" "$ctx/app.jar"
  docker build -q -t "acme/$s:$TAG" "$ctx" >/dev/null
done
ctx="$ART/.ctx/web"; mkdir -p "$ctx"
cp "$REPO_ROOT/workspace/apps/frontend/Dockerfile.web.prebuilt" "$ctx/Dockerfile"
cp -r "$ART/frontend/web/dist" "$ctx/dist"
cp "$ART/frontend/web/nginx.conf" "$ctx/nginx.conf"
docker build -q -t "acme/web:$TAG" "$ctx" >/dev/null
for s in gateway platform-auth platform-infra platform-system web; do
  docker save -o "$ART/images/$s.tar" "acme/$s:$TAG"
done
echo "✅ [images] 五镜像 tar 落盘"

cat > "$ART/meta.env" <<EOF
TAG=$TAG
SHA7=$SHA7
ENV=prod
CREATED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
echo "✅ 构建完成：$TAG（产物与 meta 见 $ART）"
