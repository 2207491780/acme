#!/bin/sh
# 用法：healthcheck.sh <dev|test|prod> —— 部署后健康检查（由 cd.yml 调用，也可本地手动执行）。
# 检查：① 全部 Pod Ready；② 经节点 80 端口（k3s Traefik hostPort）以 Ingress Host 探 web 首页。
# 任何一步失败非零退出以阻断流水线。
set -eu

[ "$#" -eq 1 ] || { echo "用法: $0 <dev|test|prod>" >&2; exit 2; }
ENV=$1
NS="acme-$ENV"

echo "▶ 等待 $NS 全部 Pod Ready"
kubectl -n "$NS" wait --for=condition=Ready pods --all --timeout=300s

# Ingress host（动态读自集群，随 overlay 域名变化；无需 DNS，以 Host 头直探节点 IP）
HOST=$(kubectl -n "$NS" get ingress acme-web -o jsonpath='{.spec.rules[0].host}')
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')

echo "▶ 探活 http://$NODE_IP/（Host: $HOST）"
i=1
while [ "$i" -le 10 ]; do
  if curl -sf -H "Host: $HOST" "http://$NODE_IP/" -o /dev/null; then
    echo "✅ 健康检查通过：web 可达，全部 Pod Ready"
    exit 0
  fi
  echo "  第 $i/10 次未就绪，10s 后重试"
  i=$((i + 1))
  sleep 10
done

echo "❌ 健康检查失败：web 不可达（Ingress host=$HOST，node=$NODE_IP）" >&2
exit 1
