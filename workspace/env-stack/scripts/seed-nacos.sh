#!/bin/sh
# 用法：seed-nacos.sh <prod|dev> —— 推送 Nacos 配置中心种子 data-id（幂等）。
# 单套 Nacos 双 namespace（2026-11-18-02 两环境：test 已并入 dev，test 为兼容别名）：
# 先确保 namespace 存在（console API），再按 namespace（tenant 参数）播种 data-id——
# 三个 platform 服务 application.yml 有 `spring.config.import: nacos:platform-*.yaml`，
# 对应 namespace 缺 data-id 会导致服务启动失败，故部署流程在 apply 后自动调用本脚本（prod）；
# dev namespace 由首次搭机时手动执行一次（本地服务经 dev-tunnel 使用）。
# 环境差异配置后续可往对应 data-id 追加（服务本地 yml 已含全部默认值，种子保持最小）。
set -eu

[ "$#" -eq 1 ] || { echo "用法: $0 <prod|dev>" >&2; exit 2; }
ENV=$1
[ "$ENV" = test ] && ENV=dev
case "$ENV" in
  prod|dev) ;;
  *) echo "❌ 环境仅支持 prod|dev（test 为 dev 别名）" >&2; exit 2 ;;
esac

# 中间件单套常驻 acme-prod namespace（2026-11-18-02），种子经 port-forward 到本机回环访问
# Nacos OpenAPI（鉴权关闭态免 token）
kubectl -n acme-prod port-forward svc/nacos 18848:8848 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill "$PF_PID" 2>/dev/null || true' EXIT

i=1
# 首部署时 nacos pod 需拉镜像+冷启动，等待窗口放宽到 5 分钟（60×5s）
while [ "$i" -le 60 ]; do
  if curl -sf -o /dev/null "http://127.0.0.1:18848/nacos/v2/console/health/readiness"; then
    break
  fi
  [ "$i" -eq 60 ] && { echo "❌ Nacos 未就绪，种子配置失败" >&2; exit 1; }
  i=$((i + 1))
  sleep 5
done

# 防呆：确认目标 namespace 已存在（列表接口含 customNamespaceId），缺失则创建（幂等先查后建）
if ! curl -sf "http://127.0.0.1:18848/nacos/v1/console/namespaces" | grep -q "\"customNamespaceId\":\"$ENV\""; then
  curl -sf -X POST "http://127.0.0.1:18848/nacos/v1/console/namespaces" \
    --data-urlencode "customNamespaceId=$ENV" \
    --data-urlencode "namespaceName=$ENV" \
    --data-urlencode "description=ACME $ENV 环境（单套中间件逻辑隔离）" >/dev/null \
    && echo "  ✓ 已创建 namespace $ENV" \
    || { echo "❌ 创建 namespace $ENV 失败" >&2; exit 1; }
fi

for id in platform-auth platform-infra platform-system platform-scheduler platform-workflow; do
  # --data-urlencode 保证 yaml 内容安全传输；tenant 即 namespaceId
  # prod：platform-infra 种子携带对象存储配置（MinIO，2026-11-19 启用）——endpoint 为公网同源地址
  # （浏览器直传预签名 URL 同源口径，CSP connect-src 'self'），access/secret 经环境变量注入不入库；
  # dev（test 并入 dev）：保持占位（本地服务走 LOCAL 落盘，ACME_STORAGE_LOCAL_ROOT_DIR 本地自定）
  # platform-workflow 增补（REQ-20261121-142316 确认⑧：BPM 引擎服务种子 data-id）
  if [ "$ENV" = prod ] && [ "$id" = platform-infra ]; then
    CONTENT="# $id 种子配置（由 seed-nacos.sh 维护，环境差异在此追加）
acme:
  storage:
    type: minio
    # ⚠️ endpoint 为裸域名（SDK 禁止带路径，no path allowed）——同源直传经 nginx 按桶路径
    #    /acme-prod/ 反代（web/nginx.conf），预签名 URL 落同源（CSP connect-src 'self'）
    endpoint: http://acmecloud.site
    bucket: acme-prod
"
  else
    CONTENT="# $id 种子配置（由 seed-nacos.sh 维护，环境差异在此追加）
$id: {}
"
  fi
  curl -sf -X POST "http://127.0.0.1:18848/nacos/v1/cs/configs" \
    --data-urlencode "dataId=$id.yaml" \
    --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "tenant=$ENV" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content=$CONTENT" >/dev/null \
    && echo "  ✓ 已推送 $id.yaml（namespace=$ENV）" \
    || { echo "❌ 推送 $id.yaml 失败" >&2; exit 1; }
done

echo "✅ Nacos 种子配置完成（namespace=$ENV）"
