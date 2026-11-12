#!/bin/sh
# 用法：seed-nacos.sh <dev|test|prod> —— 推送 Nacos 配置中心种子 data-id（幂等）。
# 三个 platform 服务 application.yml 有 `spring.config.import: nacos:platform-*.yaml`，
# data-id 不存在会导致服务启动失败，故部署流程在 apply 后自动调用本脚本。
# 环境差异配置后续可往对应 data-id 追加（服务本地 yml 已含全部默认值，种子保持最小）。
set -eu

[ "$#" -eq 1 ] || { echo "用法: $0 <dev|test|prod>" >&2; exit 2; }
ENV=$1
NS="acme-$ENV"

# 经 port-forward 到本机回环访问 Nacos OpenAPI（鉴权关闭态免 token）
kubectl -n "$NS" port-forward svc/nacos 18848:8848 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill "$PF_PID" 2>/dev/null || true' EXIT

i=1
while [ "$i" -le 15 ]; do
  if curl -sf -o /dev/null "http://127.0.0.1:18848/nacos/v2/console/health/readiness"; then
    break
  fi
  [ "$i" -eq 15 ] && { echo "❌ Nacos 未就绪，种子配置失败" >&2; exit 1; }
  i=$((i + 1))
  sleep 2
done

for id in platform-auth platform-infra platform-system; do
  # --data-urlencode 保证 yaml 内容安全传输；空配置 + 注释占位
  curl -sf -X POST "http://127.0.0.1:18848/nacos/v1/cs/configs" \
    --data-urlencode "dataId=$id.yaml" \
    --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content=# $id 种子配置（由 seed-nacos.sh 维护，环境差异在此追加）
$id: {}
" >/dev/null \
  && echo "  ✓ 已推送 $id.yaml" \
  || { echo "❌ 推送 $id.yaml 失败" >&2; exit 1; }
done

echo "✅ Nacos 种子配置完成（$NS）"
