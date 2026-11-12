#!/bin/sh
# 用法：deploy.sh <dev|test|prod> —— 部署目标环境到 k3s（在服务器上或有 kubectl 的机器执行）。
# 由 cd.yml 调用，也可本地手动执行。环境三态与 .ai/env/<env>.yaml 对齐；凭证一律经环境变量注入。
#
# 前置：
#   1. kubectl 已连目标集群（k3s 服务器上 ~/.kube/config 就位即可）
#   2. 服务器 /opt/acme/<env>/app.env 与 registry.env 已就位（模板见 env-stack/config/*.example.env）
# 镜像地址注入：把 base+overlay 拷到临时目录并追加 images 段，用 kubectl 内置 kustomize 渲染，
# 不依赖独立 kustomize 二进制，也不弄脏仓库文件。
# 幂等：可重复执行；Secret 按内容更新，rollout 等待就绪失败则非零退出。
set -eu

[ "$#" -eq 1 ] || { echo "用法: $0 <dev|test|prod>" >&2; exit 2; }
ENV=$1

ENVSTACK=$(cd "$(dirname "$0")/.." && pwd)
ENV_DIR=${ACME_ENV_DIR:-"/opt/acme/$ENV"}
APP_ENV_FILE="$ENV_DIR/app.env"
REG_ENV_FILE="$ENV_DIR/registry.env"

[ -f "$APP_ENV_FILE" ] || { echo "❌ 缺 $APP_ENV_FILE（模板: env-stack/config/app.example.env）" >&2; exit 1; }
[ -f "$REG_ENV_FILE" ] || { echo "❌ 缺 $REG_ENV_FILE（模板: env-stack/config/registry.example.env）" >&2; exit 1; }

# registry.env 只取 REGISTRY/ACME_IMAGE_TAG，避免整文件被注入应用 Secret
REGISTRY=$(grep -E '^REGISTRY=' "$REG_ENV_FILE" | head -1 | cut -d= -f2-)
FILE_TAG=$(grep -E '^ACME_IMAGE_TAG=' "$REG_ENV_FILE" | head -1 | cut -d= -f2-)
# tag 优先级：环境变量 ACME_IMAGE_TAG（CI 注入的 env-SHA 不可变 tag）> registry.env > <env>-latest
TAG=${ACME_IMAGE_TAG:-$FILE_TAG}
TAG=${TAG:-"$ENV-latest"}
[ -n "$REGISTRY" ] || { echo "❌ registry.env 缺 REGISTRY" >&2; exit 1; }

NS="acme-$ENV"
# REGISTRY 含命名空间（如 ccr.ccs.tencentyun.com/<ns>），镜像路径为 $REGISTRY/<服务名>
echo "▶ 部署 $NS（镜像仓库 $REGISTRY，tag $TAG）"

kubectl get namespace "$NS" >/dev/null 2>&1 || kubectl create namespace "$NS"

# 1) 应用密钥（deploy 侧整体注入；值只存在于集群 Secret，不落流水线日志）
kubectl -n "$NS" create secret generic acme-app-secrets \
  --from-env-file="$APP_ENV_FILE" \
  --dry-run=client -o yaml | kubectl apply -f -

# 2) 镜像仓库拉取凭证
kubectl -n "$NS" create secret docker-registry acme-registry-auth \
  --docker-server="$REGISTRY" \
  --docker-username="$(grep -E '^REGISTRY_USERNAME=' "$REG_ENV_FILE" | head -1 | cut -d= -f2-)" \
  --docker-password="$(grep -E '^REGISTRY_PASSWORD=' "$REG_ENV_FILE" | head -1 | cut -d= -f2-)" \
  --dry-run=client -o yaml | kubectl apply -f -

# 3) 渲染清单：临时目录拼装 base + overlay + images 段（registry 随 env 文件注入）
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cp -r "$ENVSTACK/k8s/base" "$TMP/base"
cp -r "$ENVSTACK/k8s/overlays/$ENV" "$TMP/overlay"
sed -i 's|\.\./\.\./base|../base|' "$TMP/overlay/kustomization.yaml"
{
  echo "images:"
  for svc in gateway platform-auth platform-infra platform-system web; do
    printf '  - name: acme/%s\n    newName: %s/%s\n    newTag: %s\n' "$svc" "$REGISTRY" "$svc" "$TAG"
  done
} >> "$TMP/overlay/kustomization.yaml"

# 4) 应用清单（apply 后种子 Nacos 配置中心，服务 spring.config.import 依赖 data-id）
kubectl apply -k "$TMP/overlay"
sh "$ENVSTACK/scripts/seed-nacos.sh" "$ENV"

# 5) 等待就绪（失败非零退出，阻断流水线）
for sts in postgres redis nacos; do
  kubectl -n "$NS" rollout status "statefulset/$sts" --timeout=300s
done
for dep in acme-gateway platform-auth platform-infra platform-system acme-web; do
  kubectl -n "$NS" rollout status "deployment/$dep" --timeout=600s
done

echo "✅ $NS 部署完成"
