#!/bin/sh
# 用法：dev-tunnel.sh <dev|test|prod> [up|down] —— 本地 ↔ 云端环境调试隧道。
# up（默认）：SSH + kubectl port-forward，把云端 PG/Redis/Nacos/网关映射到本机回环：
#   本地 15432 → 云 PG | 16379 → 云 Redis | 18848 → 云 Nacos HTTP | 19848 → Nacos gRPC | 19888 → 云网关
#   服务器侧监听 27xxx 高位端口，避开 seed-nacos.sh 的 18848，与部署并发互不干扰。
# down：清理服务器侧残留 port-forward。up 状态下 Ctrl-C 即自动拆除（SSH 会话退出连带清理）。
# 联调命令示例见脚本输出与 RUNBOOK「本地联调云端环境」。
set -eu

ENV=${1:-dev}
ACT=${2:-up}
NS="acme-$ENV"
HOST=${ACME_SRV_HOST:-124.223.118.235}
RPORT=${ACME_SRV_PORT:-22}
RUSER=${ACME_SRV_USER:-ubuntu}
KEY=${ACME_SRV_KEY:-$HOME/.ssh/acme_srv_deploy}

SSH_CMD="ssh -i $KEY -p $RPORT -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=15"

case "$ACT" in
  down)
    $SSH_CMD "$RUSER@$HOST" "pkill -f 'port-forward.* 27[0-9]{3}:'" 2>/dev/null || true
    echo "✅ 服务器侧隧道端口已清理（$NS）"
    exit 0
    ;;
  up) ;;
  *) echo "用法: $0 <dev|test|prod> [up|down]" >&2; exit 2 ;;
esac

echo "▶ 拉取云端 $NS 凭证（本地启动服务前 export）："
$SSH_CMD "$RUSER@$HOST" "
for k in PG_PASSWORD REDIS_PASSWORD NACOS_PASSWORD ACME_USER_TOKEN_HMAC_SECRET ACME_SECURITY_TOKEN_HMAC_SECRET; do
  v=\$(kubectl -n $NS get secret acme-app-secrets -o jsonpath=\"{.data.\$k}\" | base64 -d)
  printf 'export %s=\"%s\"\n' \"\$k\" \"\$v\"
done"

cat <<EOF
▶ 隧道映射（本机回环，Ctrl-C 拆除）：
  15432 → $NS/postgres    16379 → $NS/redis
  18848 → $NS/nacos(http) 19848 → $NS/nacos(grpc) 19888 → $NS/acme-gateway
▶ 本地后端联调示例（先 export 上方凭证）：
  PG_HOST=127.0.0.1 PG_PORT=15432 REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \\
  NACOS_ADDR=http://127.0.0.1:18848 \\
  mvn -f workspace/apps/backend/acme-cloud/pom.xml -pl acme-platform/platform-auth/platform-auth-service spring-boot:run
EOF

exec $SSH_CMD \
  -L 15432:127.0.0.1:27432 -L 16379:127.0.0.1:27379 \
  -L 18848:127.0.0.1:28848 -L 19848:127.0.0.1:29848 \
  -L 19888:127.0.0.1:29888 \
  "$RUSER@$HOST" "
kubectl -n $NS port-forward svc/postgres 27432:5432 --address=127.0.0.1 >/dev/null 2>&1 &
kubectl -n $NS port-forward svc/redis 27379:6379 --address=127.0.0.1 >/dev/null 2>&1 &
kubectl -n $NS port-forward svc/nacos 28848:8848 --address=127.0.0.1 >/dev/null 2>&1 &
kubectl -n $NS port-forward svc/nacos 29848:9848 --address=127.0.0.1 >/dev/null 2>&1 &
kubectl -n $NS port-forward svc/acme-gateway 29888:9888 --address=127.0.0.1 >/dev/null 2>&1 &
wait"
