#!/bin/sh
# 用法：dev-tunnel.sh <dev|test> [up|down] —— 本地 ↔ 云端环境调试隧道（prod 禁用：只读红线）。
# 前置：export ACME_SRV_HOST=<服务器地址>（必填，无缺省）；可选 ACME_SRV_PORT(22)/ACME_SRV_USER(ubuntu)/ACME_SRV_KEY(~/.ssh/acme_srv_deploy)。
# up（默认）：SSH + kubectl port-forward，把云端 PG/Redis/Nacos/网关映射到本机回环：
#   本地 15432 → 云 PG | 16379 → 云 Redis | 18848 → 云 Nacos HTTP | 19848 → Nacos gRPC | 19888 → 云网关
#   服务器侧监听 27xxx 高位端口（仅绑 127.0.0.1），避开 seed-nacos.sh 的 18848，与部署并发互不干扰。
# down：按 PID 文件 + 端口监听扫杀清理服务器侧残留（兜底异常断连）。up 状态下 Ctrl-C/断连由远端 trap 自动清理。
# ⚠️ 由用户在本机终端执行：脚本打印云端凭证 export 行，属敏感输出，不得经 AI 工具运行或转录（.ai/rules 安全红线）。
# 联调命令示例见脚本输出与 RUNBOOK「本地联调云端环境」。
set -eu

ENV=${1:-dev}
ACT=${2:-up}

case "$ENV" in
  dev|test) ;;
  prod) echo "❌ prod 禁用调试隧道（只读红线，见 .ai/rules 安全约束）" >&2; exit 1 ;;
  *) echo "用法: $0 <dev|test> [up|down]" >&2; exit 2 ;;
esac
case "$ACT" in
  up|down) ;;
  *) echo "用法: $0 <dev|test> [up|down]" >&2; exit 2 ;;
esac

NS="acme-$ENV"
HOST=${ACME_SRV_HOST:?需先 export ACME_SRV_HOST=<服务器地址>（无缺省值，避免误连）}
RPORT=${ACME_SRV_PORT:-22}
RUSER=${ACME_SRV_USER:-ubuntu}
KEY=${ACME_SRV_KEY:-$HOME/.ssh/acme_srv_deploy}

SSH_CMD="ssh -i $KEY -p $RPORT -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=15"

if [ "$ACT" = down ]; then
  # 孤儿 kubectl 的 argv 会被置零，pkill -f 匹配不到：按 PID 文件 + ss 端口监听定位，杀前校验 comm=kubectl。
  $SSH_CMD "$RUSER@$HOST" '
PF=/tmp/acme-tunnel-'"$NS"'.pids
killt() { [ "$(cat /proc/$1/comm 2>/dev/null)" = kubectl ] && kill "$1" 2>/dev/null; }
if [ -f "$PF" ]; then for p in $(cat "$PF"); do killt "$p"; done; rm -f "$PF"; fi
for p in $(ss -ltnp "sport = :27432 or sport = :27379 or sport = :28848 or sport = :29848 or sport = :29888" 2>/dev/null | grep -oE "pid=[0-9]+" | cut -d= -f2 | sort -u); do killt "$p"; done
if ss -ltn "sport = :27432 or sport = :27379 or sport = :28848 or sport = :29848 or sport = :29888" 2>/dev/null | tail -n +2 | grep -q .; then
  echo "⚠️ 仍有隧道端口监听残留，请手动检查 ss -ltnp" >&2; exit 1
fi' || { echo "❌ 清理失败（SSH 不可达或仍有残留）" >&2; exit 1; }
  echo "✅ 服务器侧隧道已清理（$NS）"
  exit 0
fi

echo "▶ 拉取云端 $NS 凭证（本地启动服务前 export）："
$SSH_CMD "$RUSER@$HOST" "
for k in PG_PASSWORD REDIS_PASSWORD NACOS_PASSWORD ACME_USER_TOKEN_HMAC_SECRET ACME_SECURITY_TOKEN_HMAC_SECRET \
         ACME_SEED_SUPERADMIN_PASSWORD ACME_SEED_DEMO_ADMIN_PASSWORD ACME_SEED_DEMO_OPERATOR_PASSWORD; do
  v=\$(kubectl -n $NS get secret acme-app-secrets -o jsonpath=\"{.data.\$k}\" | base64 -d)
  [ -n \"\$v\" ] || continue  # 集群 Secret 缺该键（旧版 app.env 未登记）时跳过，不导出空值
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
  ⚠️ platform-infra 本地启动另需 ACME_STORAGE_LOCAL_ROOT_DIR=<本机已存在目录>
    （REQ-20261114-071607 文件服务落盘根；非集群 Secret，本地自定路径，不入库）
EOF

# -tt 强制 pty：无 pty 的 SSH 会话断连时 sshd 不发 SIGHUP，远端会孤儿化；有 pty 则内核必发 HUP 触发 trap 清理。
exec $SSH_CMD -tt \
  -L 15432:127.0.0.1:27432 -L 16379:127.0.0.1:27379 \
  -L 18848:127.0.0.1:28848 -L 19848:127.0.0.1:29848 \
  -L 19888:127.0.0.1:29888 \
  "$RUSER@$HOST" "
PF=/tmp/acme-tunnel-$NS.pids
: > \"\$PF\"
trap 'kill \$(cat \"\$PF\") 2>/dev/null; rm -f \"\$PF\"' HUP INT TERM EXIT
kubectl -n $NS port-forward svc/postgres 27432:5432 --address=127.0.0.1 >/dev/null 2>&1 & echo \$! >> \"\$PF\"
kubectl -n $NS port-forward svc/redis 27379:6379 --address=127.0.0.1 >/dev/null 2>&1 & echo \$! >> \"\$PF\"
kubectl -n $NS port-forward svc/nacos 28848:8848 --address=127.0.0.1 >/dev/null 2>&1 & echo \$! >> \"\$PF\"
kubectl -n $NS port-forward svc/nacos 29848:9848 --address=127.0.0.1 >/dev/null 2>&1 & echo \$! >> \"\$PF\"
kubectl -n $NS port-forward svc/acme-gateway 29888:9888 --address=127.0.0.1 >/dev/null 2>&1 & echo \$! >> \"\$PF\"
wait"