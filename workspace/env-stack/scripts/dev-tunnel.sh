#!/bin/sh
# Windows 前置：须在 Git Bash / WSL 中运行（依赖 POSIX sh、mktemp、awk 与 POSIX 版 ssh；
# Git for Windows 的 ssh 链接 msys-2.0.dll，可识别下面 /tmp 下的 #!/bin/sh askpass 助手）。
# 用法：dev-tunnel.sh [dev] —— 本地 ↔ 云端中间件隧道（ADR 2026-11-18-02：SSH 连【应用面机】，
# 纯 ssh -L 直达中间件 ClusterIP；test 已并入 dev（别名）；prod 禁用：只读红线）。
# 前置：仓库根 cloud-servers.yml 的 app-plane 块（host/ssh_port/user/password）——认证方式 =
#   **密码**（SSH_ASKPASS 注入，规则见 .ai/rules/ai-guidelines.md 安全红线；禁止装公钥/密钥登录）。
# 前置产物（local-deploy.sh 部署后自动同步到应用面机）：
#   /opt/acme/mw-endpoints.env —— postgres/redis/nacos ClusterIP
#   /opt/acme/tunnel/dev.env   —— dev 凭证 export 行（600）
# 行为：
#   1) SSH 拉取端点与 dev.env 凭证 export 行（敏感输出由用户终端承接，不得经 AI 工具运行或转录）
#   2) 建立纯 ssh -L 转发（无远端进程，断连即拆）：
#      15432 → postgres ClusterIP | 16379 → redis ClusterIP
#      18848 → nacos ClusterIP:8848 | 19848 → nacos ClusterIP:9848（gRPC = 8848 + 1000）
#   3) 本地服务注册进 Nacos dev namespace（ACME_ENV_ID=dev），prod 网关不可见——调试期泄漏已根治。
set -eu

ENV=${1:-dev}
[ "$ENV" = test ] && ENV=dev
case "$ENV" in
  dev) ;;
  prod) echo "❌ prod 禁用调试隧道（只读红线，见 .ai/rules 安全约束）" >&2; exit 1 ;;
  *) echo "用法: $0 [dev]（test 为 dev 别名）" >&2; exit 2 ;;
esac

REPO_ROOT=$(git rev-parse --show-toplevel)
SV_FILE="$REPO_ROOT/cloud-servers.yml"
[ -f "$SV_FILE" ] || { echo "❌ 缺仓库根 cloud-servers.yml" >&2; exit 1; }
svfield() { awk -v blk="$1:" -v fld="$2:" '
    $0 ~ "^  " blk {inblk=1; next}
    inblk && /^  [a-z-]+:/ {exit}
    inblk && index($0, "    " fld) == 1 {
      sub(/^[ ]{4}[^:]*:[ ]?/, ""); gsub(/\r$/, ""); gsub(/^["'\'']|["'\'']$/, ""); print; exit
    }' "$SV_FILE" | sed -e "s/^['\"]//" -e "s/['\"]\$//"; }
H=$(svfield app-plane host); P=$(svfield app-plane ssh_port); U=$(svfield app-plane user)
PW=$(svfield app-plane password)
P=${P:-22}
: "${PW:?❌ cloud-servers.yml 的 app-plane 未填 password}"

ASK="$(mktemp -d)/.askpass.sh"
printf '#!/bin/sh\necho "$ACME_SSH_PW"\n' > "$ASK"; chmod +x "$ASK"
ssh_run() {
  export ACME_SSH_PW=$PW SSH_ASKPASS="$ASK" SSH_ASKPASS_REQUIRE=force DISPLAY=:0
  ssh -p "$P" -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive \
    -o NumberOfPasswordPrompts=1 -o StrictHostKeyChecking=accept-new -o ConnectTimeout=8 \
    -o ServerAliveInterval=15 -o LogLevel=ERROR "$U@$H" "$@"
}

echo "▶ 拉取中间件端点（应用面机 /opt/acme/mw-endpoints.env）："
EP=$(ssh_run '. /opt/acme/mw-endpoints.env && echo "PG=$postgres REDIS=$redis NACOS=$nacos"')
PG_IP=${EP#PG=};   PG_IP=${PG_IP%% *}
REDIS_IP=${EP#*REDIS=}; REDIS_IP=${REDIS_IP%% *}
NACOS_IP=${EP##*NACOS=}
[ -n "$PG_IP" ] && [ -n "$REDIS_IP" ] && [ -n "$NACOS_IP" ] \
  || { echo "❌ 端点缺失（$EP）——先跑 local-deploy.sh（部署后自动同步端点文件）" >&2; exit 1; }
echo "  postgres/redis/nacos 端点已取得"

echo "▶ 拉取云端 dev 凭证（本地启动服务前 export；敏感输出由用户终端承接）："
ssh_run "cat /opt/acme/tunnel/dev.env"

cat <<'EOF'
▶ 隧道映射（本机回环，Ctrl-C 拆除，无远端进程）：
  15432 → postgres ClusterIP:5432   16379 → redis ClusterIP:6379
  18848 → nacos ClusterIP:8848      19848 → nacos ClusterIP:9848（gRPC）
▶ 本地后端联调示例（先 export 上方凭证）：
  PG_HOST=127.0.0.1 PG_PORT=15432 REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \
  NACOS_ADDR=http://127.0.0.1:18848 \
  ${MVN_CMD:-mvn} -f workspace/apps/backend/acme-cloud/pom.xml -pl acme-platform/platform-auth/platform-auth-service spring-boot:run
  ⚠️ Maven 不在 PATH 时先 export MVN_CMD=<maven-home>/bin/mvn（Windows/macOS 同口径）
  ⚠️ platform-infra 本地启动另需 ACME_STORAGE_LOCAL_ROOT_DIR=<本机已存在目录>
    （文件服务落盘根；非集群 Secret，本地自定路径，不入库）
EOF

exec ssh -p "$P" \
  -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive \
  -o NumberOfPasswordPrompts=1 -o StrictHostKeyChecking=accept-new -o ConnectTimeout=8 \
  -o ServerAliveInterval=15 -o LogLevel=ERROR \
  -L 15432:$PG_IP:5432 -L 16379:$REDIS_IP:6379 \
  -L 18848:$NACOS_IP:8848 -L 19848:$NACOS_IP:9848 \
  "$U@$H"
