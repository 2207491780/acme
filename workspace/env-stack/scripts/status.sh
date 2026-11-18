#!/bin/sh
# 用法：status.sh <dev|test|prod> —— 一屏总览指定环境的健康状态（Pod/镜像版本/资源/探活）。
# 看全部环境：kubectl get pods -A | grep '^acme-'（每个环境一个 namespace）。
set -eu

[ "$#" -eq 1 ] || { echo "用法: $0 <dev|test|prod>" >&2; exit 2; }
ENV=$1
NS="acme-$ENV"

if ! kubectl get namespace "$NS" >/dev/null 2>&1; then
  echo "❌ 命名空间 $NS 不存在（该环境未部署；部署：deploy.sh $ENV）"
  exit 1
fi

echo "═══ Pod（$NS）═══"
kubectl -n "$NS" get pods
echo
echo "═══ 工作负载 ═══"
kubectl -n "$NS" get deploy,sts 2>/dev/null
echo
echo "═══ 镜像版本 ═══"
kubectl -n "$NS" get pods -o jsonpath='{range .items[*]}{.metadata.name}{"  "}{.spec.containers[0].image}{"\n"}{end}' 2>/dev/null
echo
echo "═══ 实时资源占用 ═══"
kubectl -n "$NS" top pods 2>/dev/null || echo "（metrics-server 未就绪，稍后再试）"
echo
echo "═══ web 探活 ═══"
HOST=$(kubectl -n "$NS" get ingress acme-web -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || true)
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null)
if [ -n "$HOST" ] && [ -n "$NODE_IP" ]; then
  if curl -sf -m 5 -H "Host: $HOST" "http://$NODE_IP/" -o /dev/null; then
    echo "✅ web 可达：http://$NODE_IP/（Host: $HOST）"
  else
    echo "❌ web 不可达（Host: $HOST）"
    exit 1
  fi
else
  echo "（无 Ingress/节点信息，跳过探活）"
fi
