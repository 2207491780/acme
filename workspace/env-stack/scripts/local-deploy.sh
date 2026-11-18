#!/bin/sh
# 用法：local-deploy.sh —— 消费本地产物部署（ADR 2026-11-18-03）：镜像推 TCR → 数据面机 deploy →
# 隧道面同步（中间件端点 + dev 凭证到应用面机）。
# 输入接口：产物目录（local-build.sh 产出；缺省取 .ai-tmp/build/ 下最新 prod-*，可 export
#   ACME_ART_DIR=<产物目录> 指定——支持跨目录/跨机复用同一份产物）。
# 前置（本机，仓库根执行）：
#   ① 镜像仓库凭证（键名与服务器 registry.env 同源）：export REGISTRY=<registry地址/命名空间>
#      REGISTRY_USERNAME=<用户> REGISTRY_PASSWORD=<密码>，或写入 .ai-tmp/registry.env 私有文件
#      （脚本自动 source；真实值不入库、不经 AI 工具运行或转录）；
#   ② 两机 SSH 坐标与口令取自仓库根 cloud-servers.yml（data-plane=部署目标 / app-plane=隧道对端），
#      认证方式 = **密码**（SSH_ASKPASS 注入，规则见 .ai/rules/ai-guidelines.md 安全红线；
#      禁止装公钥/密钥登录）；凭证全程不回显、不经 AI 工具运行或转录；
#   ③ 数据面机 /opt/acme/prod/{app.env,registry.env} 已就位（RUNBOOK 第 3/4 步）。
set -eu

REPO_ROOT=$(git rev-parse --show-toplevel)
ART=${ACME_ART_DIR:-"$(ls -d "$REPO_ROOT"/.ai-tmp/build/prod-* 2>/dev/null | sort | tail -1)"}
[ -n "$ART" ] && [ -f "$ART/meta.env" ] \
  || { echo "❌ 未找到产物目录（先跑 local-build.sh，或 export ACME_ART_DIR=<产物目录>）" >&2; exit 1; }
# 产物元数据（TAG/SHA7/ENV/CREATED）
# shellcheck disable=SC1090
. "$ART/meta.env"
echo "▶ 部署产物：$ART（$TAG）"

if [ -f "$REPO_ROOT/.ai-tmp/registry.env" ]; then
  # shellcheck disable=SC1091
  . "$REPO_ROOT/.ai-tmp/registry.env"
fi
: "${REGISTRY:?需 export REGISTRY=<registry地址/命名空间>（与服务器 registry.env 同键名；或写入 .ai-tmp/registry.env）}"
: "${REGISTRY_USERNAME:?需 export REGISTRY_USERNAME=<用户>}"
: "${REGISTRY_PASSWORD:?需 export REGISTRY_PASSWORD=<密码>}"
# 登录 host 取 REGISTRY 首段（命名空间不属于认证 host）；REGISTRY_HOST 可显式覆盖
LOGIN_HOST=${REGISTRY_HOST:-${REGISTRY%%/*}}

echo "$REGISTRY_PASSWORD" | docker login "$LOGIN_HOST" -u "$REGISTRY_USERNAME" --password-stdin
for s in gateway platform-auth platform-infra platform-system web; do
  # 本机已有镜像直接用；否则从产物 tar 载入（跨机复用同一份产物）
  docker image inspect "acme/$s:$TAG" >/dev/null 2>&1 || docker load -i "$ART/images/$s.tar"
  docker tag "acme/$s:$TAG" "$REGISTRY/$s:$TAG"
  docker push "$REGISTRY/$s:$TAG"
done
echo "✅ 五镜像已推送：$REGISTRY/{gateway,platform-auth,platform-infra,platform-system,web}:$TAG"

# ── SSH 密码认证层（口令经 SSH_ASKPASS 注入，禁止公钥；两机坐标取 cloud-servers.yml）──
SV_FILE="$REPO_ROOT/cloud-servers.yml"
[ -f "$SV_FILE" ] || { echo "❌ 缺仓库根 cloud-servers.yml" >&2; exit 1; }
svfield() { awk -v blk="$1:" -v fld="$2:" '
    $0 ~ "^  " blk {inblk=1; next}
    inblk && /^  [a-z-]+:/ {exit}
    inblk && index($0, "    " fld) == 1 {
      sub(/^[ ]{4}[^:]*:[ ]?/, ""); gsub(/\r$/, ""); gsub(/^["'\'']|["'\'']$/, ""); print; exit
    }' "$SV_FILE" | sed -e "s/^['\"]//" -e "s/['\"]\$//"; }
ASK="$(mktemp -d)/.askpass.sh"
printf '#!/bin/sh\necho "$ACME_SSH_PW"\n' > "$ASK"; chmod +x "$ASK"
ssh_run() { # ssh_run <host> <port> <user> <password> <远程命令…>
  _h=$1; _p=$2; _u=$3; _w=$4; shift 4
  export ACME_SSH_PW=$_w SSH_ASKPASS="$ASK" SSH_ASKPASS_REQUIRE=force DISPLAY=:0
  ssh -p "$_p" -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive \
    -o NumberOfPasswordPrompts=1 -o StrictHostKeyChecking=accept-new -o ConnectTimeout=8 \
    -o LogLevel=ERROR "$_u@$_h" "$@"
}
scp_run() { # scp_run <host> <port> <user> <password> <本地文件或目录> <远程路径>
  _h=$1; _p=$2; _u=$3; _w=$4; _l=$5; _r=$6
  export ACME_SSH_PW=$_w SSH_ASKPASS="$ASK" SSH_ASKPASS_REQUIRE=force DISPLAY=:0
  scp -r -P "$_p" -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive \
    -o NumberOfPasswordPrompts=1 -o StrictHostKeyChecking=accept-new -o ConnectTimeout=8 \
    -o LogLevel=ERROR "$_l" "$_u@$_h:$_r"
}

DP_HOST=$(svfield data-plane host); DP_PORT=$(svfield data-plane ssh_port); DP_USER=$(svfield data-plane user)
DP_PW=$(svfield data-plane password)
AP_HOST=$(svfield app-plane host); AP_PORT=$(svfield app-plane ssh_port); AP_USER=$(svfield app-plane user)
AP_PW=$(svfield app-plane password)
DP_PORT=${DP_PORT:-22}; AP_PORT=${AP_PORT:-22}

# 数据面部署：同步 env-stack → deploy.sh prod（含种子 Nacos 与端点文件）→ healthcheck
# 目录同步走 tar 管道（scp -r 对已存在远端目录会嵌套复制，实测教训）；并清掉历史嵌套残留
ssh_run "$DP_HOST" "$DP_PORT" "$DP_USER" "$DP_PW" \
  "mkdir -p /opt/acme/src/workspace && rm -rf /opt/acme/src/workspace/env-stack"
tar -C "$REPO_ROOT/workspace" -cf - env-stack | \
  ssh_run "$DP_HOST" "$DP_PORT" "$DP_USER" "$DP_PW" "tar -xf - -C /opt/acme/src/workspace"
ssh_run "$DP_HOST" "$DP_PORT" "$DP_USER" "$DP_PW" \
  "ACME_IMAGE_TAG=$TAG bash /opt/acme/src/workspace/env-stack/scripts/deploy.sh prod"
ssh_run "$DP_HOST" "$DP_PORT" "$DP_USER" "$DP_PW" \
  "bash /opt/acme/src/workspace/env-stack/scripts/healthcheck.sh prod"
echo "✅ prod 部署完成（$TAG），建议 kubectl -n acme-prod get pods 人工复核"

# ── 隧道面同步（应用面机，dev-tunnel v2 消费；test 已并入 dev，同一套 dev 凭证）──
TMP_LOCAL=$(mktemp -d)
trap 'rm -rf "$TMP_LOCAL"' EXIT

# ① 中间件端点：数据面 deploy.sh 已生成 → 取回 → 应用面（dev-tunnel 以 ClusterIP 建转发）
ssh_run "$DP_HOST" "$DP_PORT" "$DP_USER" "$DP_PW" "cat /opt/acme/prod/mw-endpoints.env" \
  > "$TMP_LOCAL/mw-endpoints.env"
[ -s "$TMP_LOCAL/mw-endpoints.env" ] || { echo "❌ 端点文件为空" >&2; exit 1; }

# ② dev 凭证：集群 Secret → 消费者键名（prod 键不导出；常量键直接写定）
ssh_run "$DP_HOST" "$DP_PORT" "$DP_USER" "$DP_PW" "
for pair in PG_PASSWORD:PG_DEV_PASSWORD REDIS_PASSWORD:REDIS_DEV_PASSWORD NACOS_PASSWORD:NACOS_PASSWORD \
            ACME_USER_TOKEN_HMAC_SECRET:ACME_USER_TOKEN_HMAC_SECRET \
            ACME_SECURITY_TOKEN_HMAC_SECRET:ACME_SECURITY_TOKEN_HMAC_SECRET \
            ACME_SEED_SUPERADMIN_PASSWORD:ACME_SEED_SUPERADMIN_PASSWORD \
            ACME_SEED_DEMO_ADMIN_PASSWORD:ACME_SEED_DEMO_ADMIN_PASSWORD \
            ACME_SEED_DEMO_OPERATOR_PASSWORD:ACME_SEED_DEMO_OPERATOR_PASSWORD; do
  out=\${pair%%:*}; src=\${pair##*:}
  v=\$(kubectl -n acme-prod get secret acme-app-secrets -o jsonpath=\"{.data.\$src}\" | base64 -d)
  [ -n \"\$v\" ] || continue
  printf 'export %s=\"%s\"\n' \"\$out\" \"\$v\"
done" > "$TMP_LOCAL/creds.body"
{
  printf '# dev 环境凭证（test 并入 dev；由 local-deploy.sh 同步，勿提交勿转录）\n'
  printf 'export PG_DB=acme_dev\nexport PG_USER=acme_dev\n'
  printf 'export REDIS_USERNAME=acme_dev\nexport REDIS_DB=1\n'
  printf 'export NACOS_NAMESPACE=dev\nexport ACME_ENV_ID=dev\n'
  # Kafka 本地开发走 EXTERNAL 监听器（数据面公网 IP:9092，防火墙源限开发者 IP）
  printf 'export KAFKA_ADDR=%s:9092\n' "$DP_HOST"
  cat "$TMP_LOCAL/creds.body"
} > "$TMP_LOCAL/dev.env"
chmod 600 "$TMP_LOCAL/dev.env"

ssh_run "$AP_HOST" "$AP_PORT" "$AP_USER" "$AP_PW" "mkdir -p /opt/acme/tunnel && chmod 700 /opt/acme/tunnel"
scp_run "$AP_HOST" "$AP_PORT" "$AP_USER" "$AP_PW" "$TMP_LOCAL/mw-endpoints.env" "/opt/acme/mw-endpoints.env"
scp_run "$AP_HOST" "$AP_PORT" "$AP_USER" "$AP_PW" "$TMP_LOCAL/dev.env" "/opt/acme/tunnel/dev.env"
ssh_run "$AP_HOST" "$AP_PORT" "$AP_USER" "$AP_PW" "chmod 600 /opt/acme/mw-endpoints.env /opt/acme/tunnel/dev.env"
echo "✅ 隧道面已同步：/opt/acme/mw-endpoints.env + /opt/acme/tunnel/dev.env（600）"
