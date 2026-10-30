# env-stack/ — 运行环境搭建

存放「把整套环境跑起来」所需的一切：容器编排、初始化脚本、环境配置模板。目标：新机器/新协作者**只按本目录**就能从零搭出 dev（及 test）全套运行环境。

## 骨架

```text
env-stack/
├── ENV-STACK.md   # 本文件：约定说明
├── docker/        # 容器编排：compose 文件与 Dockerfile，按环境命名（compose.dev.yml / compose.test.yml）
├── scripts/       # 初始化/引导脚本（bootstrap、seed、reset 等）
└── config/        # 环境配置模板（*.example 或 ${VAR} 占位；真实配置不入库）
```

## 约定

- 环境三态对齐 `.ai/env/{dev,test,prod}.yaml`：本目录负责**搭**，`.ai/env/` 负责**连**（MCP）。
- 真实凭证不入库：模板一律 `${VAR}` 占位，配合仓库根 `.env`（已 gitignore）使用。
- 构建产物、容器卷、下载解压物放根目录 `.ai-tmp/`，不放本目录。
- 每个脚本入口写清用法注释（`# 用法：…`），AI 与人都能直接执行。
