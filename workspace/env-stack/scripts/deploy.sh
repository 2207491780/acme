#!/bin/sh
# 用法：deploy.sh prod —— 部署 prod 到 k3s（数据面机执行，由本机 local-deploy.sh SSH 触发，ADR
# 2026-11-18-03）。dev/test 云端已退役：本地服务经 dev-tunnel 连中间件（dev 凭证，test 并入 dev），
# 见 .ai-memory/playbooks/two-tier-deployment.md。凭证一律经环境变量注入。
#
# 前置：
#   1. kubectl 已连目标集群（k3s 服务器上 ~/.kube/config 就位即可）
#   2. 数据面机 /opt/acme/prod/app.env 与 registry.env 已就位（模板见 env-stack/config/*.example.env，
#      app.env 须含双环境中间件键 PG_DEV_PASSWORD / REDIS_DEV_PASSWORD）
# 镜像地址注入：把 base+overlay 拷到临时目录并追加 images 段，用 kubectl 内置 kustomize 渲染，
# 不依赖独立 kustomize 二进制，也不弄脏仓库文件。
# 幂等：可重复执行；Secret 按内容更新，rollout 等待就绪失败则非零退出。
set -eu

[ "$#" -eq 1 ] || { echo "用法: $0 prod" >&2; exit 2; }
ENV=$1
case "$ENV" in
  prod) ;;
  *) echo "❌ 仅支持 prod（dev/test 云端已退役，见 .ai-memory/playbooks/two-tier-deployment.md）" >&2; exit 2 ;;
esac

ENVSTACK=$(cd "$(dirname "$0")/.." && pwd)
ENV_DIR=${ACME_ENV_DIR:-"/opt/acme/$ENV"}
APP_ENV_FILE="$ENV_DIR/app.env"
REG_ENV_FILE="$ENV_DIR/registry.env"

# 部署互斥锁（2026-11-20）：防两会话/双机同时发版交错滚动。mkdir 原子取锁；持锁窗口 30 分钟
# （进程崩溃靠过期抢占自愈）；结束/失败经 trap 清理。确认无并发部署时可手动删锁目录重试。
LOCK_DIR="/opt/acme/$ENV/.deploy-lock"
if mkdir "$LOCK_DIR" 2>/dev/null; then
  date +%s > "$LOCK_DIR/acquired-at"
  trap 'rm -rf "$LOCK_DIR"' EXIT
else
  ACQ=$(cat "$LOCK_DIR/acquired-at" 2>/dev/null || echo 0)
  AGE=$(( $(date +%s) - ACQ ))
  if [ "$AGE" -lt 1800 ]; then
    echo "❌ 检测到进行中的部署（$LOCK_DIR，${AGE}s 前取得锁）；确认无并发后手动删除该目录重试" >&2
    exit 1
  fi
  echo "⚠️ 锁已过期（${AGE}s > 1800s，上次部署可能崩溃），抢占续用"
  rm -rf "$LOCK_DIR"
  mkdir "$LOCK_DIR"
  date +%s > "$LOCK_DIR/acquired-at"
  trap 'rm -rf "$LOCK_DIR"' EXIT
fi

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
# 服务选择（增量发布）：ACME_SERVICES = 空格分隔子集，缺省=全量七服务（local-deploy.sh 透传）；
# 未选中服务在 images 段钉住集群当前 tag——渲染清单不变 → 不触发滚动（首次部署无存量
# Deployment 时回退全局 TAG）；非法名 fail-fast（与 ACME_BUILD/DEPLOY_SERVICES 同一套服务名）
# platform-scheduler 增补（REQ-20261121-071002 确认⑥）+ platform-workflow 增补
# （REQ-20261121-142316 确认⑧：ALL_SERVICES + 镜像映射）
ALL_SERVICES='gateway platform-auth platform-infra platform-system platform-scheduler platform-workflow web'
SERVICES=${ACME_SERVICES:-$ALL_SERVICES}
for s in $SERVICES; do
  case " $ALL_SERVICES " in
    *" $s "*) ;;
    *) echo "❌ ACME_SERVICES 含非法服务名：$s（合法值：$ALL_SERVICES）" >&2; exit 1 ;;
  esac
done
# REGISTRY 含命名空间（如 ccr.ccs.tencentyun.com/<ns>），镜像路径为 $REGISTRY/<服务名>
echo "▶ 部署 $NS（镜像仓库 $REGISTRY，tag $TAG，服务：$SERVICES）"

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

# 3) 渲染清单：临时目录拼装 base + overlay + images 段（registry 随 env 文件注入）；
#    选中服务 → 新 TAG；未选中 → 钉住集群当前 tag（增量发布不滚动存量服务）
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cp -r "$ENVSTACK/k8s/base" "$TMP/base"
cp -r "$ENVSTACK/k8s/overlays/$ENV" "$TMP/overlay"
# 可移植写法：BSD sed（macOS）的 -i 必须带备份后缀参数，改用临时文件 + mv（POSIX 通用）
sed 's|\.\./\.\./base|../base|' "$TMP/overlay/kustomization.yaml" > "$TMP/overlay/kustomization.yaml.tmp"
mv "$TMP/overlay/kustomization.yaml.tmp" "$TMP/overlay/kustomization.yaml"
{
  echo "images:"
  for svc in $ALL_SERVICES; do
    case " $SERVICES " in *" $svc "*)
      printf '  - name: acme/%s\n    newName: %s/%s\n    newTag: %s\n' "$svc" "$REGISTRY" "$svc" "$TAG"
      ;;
    *)
      case "$svc" in gateway|web) dep="acme-$svc" ;; *) dep="$svc" ;; esac
      cur=$(kubectl -n "$NS" get deployment "$dep" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | sed 's/.*://')
      [ -n "$cur" ] || cur=$TAG
      printf '  - name: acme/%s\n    newName: %s/%s\n    newTag: %s\n' "$svc" "$REGISTRY" "$svc" "$cur"
      ;;
    esac
  done
} >> "$TMP/overlay/kustomization.yaml"

# 4) 应用清单（先等中间件就绪，再种子 Nacos 配置中心——首部署 nacos 需拉镜像+冷启动，
#    种子过早会失败；服务 spring.config.import 依赖 data-id）
kubectl apply -k "$TMP/overlay"

# 5) 等待中间件就绪（失败非零退出，阻断流水线）
for sts in postgres redis nacos; do
  kubectl -n "$NS" rollout status "statefulset/$sts" --timeout=300s
done
# REQ-20261117-045754：minio 清单已交付但存量环境默认未部署——硬编码进等待清单会让未部署环境
# rollout 超时致部署失败，改为存在性判断后再等待（已部署则照常等待并保持失败外抛）
if kubectl -n "$NS" get statefulset minio >/dev/null 2>&1; then
  kubectl -n "$NS" rollout status "statefulset/minio" --timeout=300s
else
  echo "ℹ️ minio 未部署（REQ-20261117-045754 交付未启用），跳过 rollout 等待"
fi

# 6) 种子 Nacos（中间件就绪后执行）
sh "$ENVSTACK/scripts/seed-nacos.sh" "$ENV"
for dep in acme-gateway platform-auth platform-infra platform-system platform-scheduler platform-workflow acme-web; do
  # 首次部署需从 TCR 公网拉取镜像（跨账号轻量机走公网路径，带宽受限）——等待上限 1800s；
  # 镜像已在节点缓存的重部署会提前秒级完成，无副作用
  kubectl -n "$NS" rollout status "deployment/$dep" --timeout=1800s
done

echo "✅ $NS 部署完成（服务：$SERVICES）"

# 6) 中间件端点文件（数据面留存 → local-deploy.sh 同步应用面机，dev-tunnel v2 以 ClusterIP 建转发；
#    ClusterIP 随 Service 重建才变化，deploy 每次重写保证新鲜）
ENDPOINTS_FILE="/opt/acme/$ENV/mw-endpoints.env"
kubectl -n "$NS" get svc postgres redis nacos \
  -o jsonpath='{range .items[*]}{.metadata.name}={.spec.clusterIP}{"\n"}{end}' > "$ENDPOINTS_FILE"
echo "ℹ️ 中间件端点已写 $ENDPOINTS_FILE（postgres/redis/nacos ClusterIP）"
