#!/bin/sh
# 用法：deploy.sh <dev|test|prod> —— 部署目标环境（由 cd.yml 调用，也可本地手动执行）。
# 环境三态与 .ai/env/<env>.yaml 对齐；凭证一律经环境变量注入（Secrets 约定见 .ai/ci/CI-GUIDE.md）。
# TODO：实现实际部署逻辑（容器编排 / 镜像推送 / 远端执行等）。
set -eu

[ "$#" -eq 1 ] || { echo "用法: $0 <dev|test|prod>" >&2; exit 2; }

echo "❌ TODO：deploy.sh 尚未实现（目标环境: $1）。"
echo "   请在 workspace/env-stack/scripts/deploy.sh 实现部署逻辑（当前占位显式失败，避免流水线假绿）。"
exit 1
