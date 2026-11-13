# .ai/env/ — 运行环境与 MCP 连接定义

告诉 AI Coding 工具「各运行环境是什么、如何通过 MCP 直连」的唯一事实源：当任务需要更强的环境操作能力（查数据库、调内部接口、观测日志等）时，**按此处的定义建立 MCP 连接**，而不是让用户手动搬运信息。
**当前运行环境**（本地调试时后端连接的环境）由 `.ai/ai-coding-config.yml` 的 `environment.file` 指向本目录中的一个文件声明。

## 文件

| 文件 | env 字段 | 环境 | 默认权限 |
|---|---|---|---|
| [local.yaml](local.yaml) | local | 本地（中间件本地运行） | 读写 |
| [cloud-dev.yaml](cloud-dev.yaml) | dev | 云端开发（k3s acme-dev） | 读写 |
| [cloud-test.yaml](cloud-test.yaml) | test | 云端测试（k3s acme-test） | 读写 |
| [cloud-prod.yaml](cloud-prod.yaml) | prod | 云端生产（k3s acme-prod） | **只读**；任何写操作需用户明确批准 |

文件名见名知意（local / cloud-<stage>）；`env` 字段保持部署阶段名，与 k8s overlays 和 CD 环境对齐。

## Schema

```yaml
env: dev                          # 部署阶段标识（dev/test/prod/local）
description: …                    # 一句话说明（运行位置、中间件、权限）
mcp:
  servers:
    - name: <server-id>           # 唯一标识（kebab-case）
      transport: stdio | http | sse
      url: <…>                    # http/sse 必填
      command: [ … ]              # stdio 必填
      env:
        KEY: ${VAR}               # 敏感值一律用环境变量引用
notes: …                          # 该环境注意事项
```

## 纪律

- ❌ **禁止在 yaml 中写明文密钥/Token**：用 `${VAR}` 引用，变量放本地 `.env`（已 gitignore）或密钥管理；`.ai/rules` 安全红线同样适用于本目录。
- 本目录是**源**（AI 按需读取，不派生）；各工具原生 MCP 配置（根 `.mcp.json`、`.cursor/mcp.json` 等）由此手动适配，映射见 `.ai/ARCHITECTURE.md`「MCP 服务器配置」。
- 环境的**搭建**在 `workspace/env-stack/`，本目录只管**连接**与运行环境定义。
- 切换当前运行环境：改 `.ai/ai-coding-config.yml` 的 `environment.file` 并重跑 `node .ai/sync.mjs`。
- 新增环境（如 cloud-staging）：按命名新建 `<name>.yaml` 并在此表登记。
