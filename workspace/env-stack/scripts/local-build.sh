#!/bin/sh
# 用法：local-build.sh —— 本机构建产物（ADR 2026-11-18-03：CI/CD 本地化；构建与部署以产物目录为接口，
# build 零凭证、deploy 凭证/网络分离，两步可分别执行）。
# 产物目录：.ai-tmp/build/<env>-<sha7>/（gitignore 不入库；ENV-STACK 约定构建产物放根 .ai-tmp/）
#   backend/       五个可执行 jar：gateway.jar / platform-auth.jar / platform-infra.jar / platform-system.jar / platform-scheduler.jar
#   frontend/web/  dist + nginx.conf
#   images/        六个镜像 tar（docker save；镜像名 acme/<名>:<tag> 无 registry 前缀——前缀由 deploy 侧 docker tag）
#   meta.env       TAG/SHA7/ENV/CREATED —— deploy 的唯一输入接口
# 前置（本机，仓库根执行）：Docker；Maven（不在 PATH 时 export MVN_CMD=完整路径，唯一口径见
#   .ai-memory/playbooks/flex-test-assembly.md「构建与运行」）；Node≥22.13 + pnpm11.8。
# 工具链以本机为准（JDK25，与镜像内 temurin-25 同源）；本地打包 skipTests，测试门禁由 ci.yml 承担。
set -eu

REPO_ROOT=$(git rev-parse --show-toplevel)
SHA7=$(git rev-parse --short=7 HEAD)
TAG="prod-$SHA7"
ART="$REPO_ROOT/.ai-tmp/build/$TAG"

# 服务选择（增量发布）：ACME_BUILD_SERVICES = 空格分隔子集，缺省=全量六服务；
# 非法名 fail-fast（与 local-deploy.sh/deploy.sh 的 ACME_DEPLOY_SERVICES/ACME_SERVICES 同一套服务名）
# platform-scheduler 增补（REQ-20261121-071002 确认⑥：与服务名注册表保持同一套）
ALL_SERVICES='gateway platform-auth platform-infra platform-system platform-scheduler web'
SERVICES=${ACME_BUILD_SERVICES:-$ALL_SERVICES}
for s in $SERVICES; do
  case " $ALL_SERVICES " in
    *" $s "*) ;;
    *) echo "❌ ACME_BUILD_SERVICES 含非法服务名：$s（合法值：$ALL_SERVICES）" >&2; exit 1 ;;
  esac
done
NEED_BACKEND=0
for s in gateway platform-auth platform-infra platform-system platform-scheduler; do
  case " $SERVICES " in *" $s "*) NEED_BACKEND=1 ;; esac
done
NEED_WEB=0
case " $SERVICES " in *" web "*) NEED_WEB=1 ;; esac

echo "▶ 产物目录：$ART（服务：$SERVICES）"
rm -rf "$ART"
mkdir -p "$ART/images"
[ "$NEED_BACKEND" = 1 ] && mkdir -p "$ART/backend"
[ "$NEED_WEB" = 1 ] && mkdir -p "$ART/frontend/web"

if [ "$NEED_BACKEND" = 1 ]; then
echo "▶ [backend] Maven：先 install acme-parent（flatten+revision 需父 POM 入库，同自包含 Dockerfile 口径）"
# clean 必带：package 不清理 target，src 资源改名/删除后会残留并打进 jar
# （2026-11-20 事故：V21 迁移重编号 V23 后旧文件残留，Flyway 双解析仍校验失败）
(cd "$REPO_ROOT/workspace/apps/backend/acme-cloud" \
 && "${MVN_CMD:-mvn}" -B -q -f ../acme-parent/pom.xml install \
 && "${MVN_CMD:-mvn}" -B -q -DskipTests \
      -pl acme-gateway,acme-platform/platform-auth/platform-auth-service,acme-platform/platform-infra/platform-infra-service,acme-platform/platform-system/platform-system-service,acme-platform/platform-scheduler/platform-scheduler-service \
      -am clean package)
B="$REPO_ROOT/workspace/apps/backend/acme-cloud"
# jar 取用口径同自包含 Dockerfile：各服务取 *-exec.jar；gateway 主构件即可执行 jar（剔 .original）
for s in gateway platform-auth platform-infra platform-system platform-scheduler; do
  case " $SERVICES " in
    *" $s "*) ;;
    *) continue ;;
  esac
  case "$s" in
    gateway) jar=$(ls "$B/acme-gateway"/target/*.jar | grep -v '\.original$' | head -1) ;;
    *) jar=$(ls "$B/acme-platform/$s/$s-service"/target/*-exec.jar | head -1) ;;
  esac
  cp "$jar" "$ART/backend/$s.jar"
done
echo "✅ [backend] jar 落盘（$SERVICES）"
fi

if [ "$NEED_WEB" = 1 ]; then
echo "▶ [frontend] pnpm 构建 @acme/web（命令口径同自包含 Dockerfile.web）"
(cd "$REPO_ROOT/workspace/apps/frontend" \
 && pnpm install --frozen-lockfile --filter @acme/web... \
 && pnpm --filter @acme/web build)
cp -r "$REPO_ROOT/workspace/apps/frontend/web/dist" "$ART/frontend/web/dist"
cp "$REPO_ROOT/workspace/apps/frontend/web/nginx.conf" "$ART/frontend/web/nginx.conf"
echo "✅ [frontend] dist 落盘"
fi

echo "▶ [images] 镜像构建（消费产物，轻量 prebuilt Dockerfile，产物 staging 为独立上下文）"
for s in gateway platform-auth platform-infra platform-system platform-scheduler; do
  case " $SERVICES " in *" $s "*) ;; *) continue ;; esac
  ctx="$ART/.ctx/$s"; mkdir -p "$ctx"
  cp "$REPO_ROOT/workspace/apps/backend/Dockerfile.prebuilt" "$ctx/Dockerfile"
  cp "$ART/backend/$s.jar" "$ctx/app.jar"
  docker build -q -t "acme/$s:$TAG" "$ctx" >/dev/null
done
if [ "$NEED_WEB" = 1 ]; then
  ctx="$ART/.ctx/web"; mkdir -p "$ctx"
  cp "$REPO_ROOT/workspace/apps/frontend/Dockerfile.web.prebuilt" "$ctx/Dockerfile"
  cp -r "$ART/frontend/web/dist" "$ctx/dist"
  cp "$ART/frontend/web/nginx.conf" "$ctx/nginx.conf"
  docker build -q -t "acme/web:$TAG" "$ctx" >/dev/null
fi
for s in $SERVICES; do
  docker save -o "$ART/images/$s.tar" "acme/$s:$TAG"
done
echo "✅ [images] 镜像 tar 落盘（$SERVICES）"

cat > "$ART/meta.env" <<EOF
TAG=$TAG
SHA7=$SHA7
ENV=prod
CREATED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
echo "✅ 构建完成：$TAG（产物与 meta 见 $ART）"
