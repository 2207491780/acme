#!/bin/sh
# 用法：healthcheck.sh <dev|test|prod> —— 部署后的健康检查（由 cd.yml 调用）。
# TODO：实现实际探活（HTTP /healthz、关键依赖连通性等）；失败应非零退出以阻断流水线。
set -eu

[ "$#" -eq 1 ] || { echo "用法: $0 <dev|test|prod>" >&2; exit 2; }

echo "❌ TODO：healthcheck.sh 尚未实现（目标环境: $1）。"
echo "   请在 workspace/env-stack/scripts/healthcheck.sh 实现探活逻辑。"
exit 1
