# workspace/ — 实际工作目录

本仓库双职责分层：`.ai/`（AI-Coding 配置工程）与 `.ai-memory/`（知识记忆）在仓库根；**实际业务与运行环境全部收敛在本目录**。

```text
workspace/
├── apps/        # 应用代码：一个目录一个可独立部署的应用（约定见 apps/APPS.md）
└── env-stack/   # 运行环境搭建：编排、脚本、配置模板（约定见 env-stack/ENV-STACK.md）
```

## 与其他目录的关系

- 环境的**搭建**在 `env-stack/`，环境的**连接**（AI 经 MCP 直连）定义在 `.ai/env/*.yaml`。
- AI 规范全局统一，由根目录 `.ai/` 派生；本目录内**不放**任何 AI 工具配置文件。
- 中间产物/临时文件放根目录 `.ai-tmp/`（不入库）。
