# .ai/env/ — 环境与 MCP 连接定义

告诉 AI Coding 工具「如何通过 MCP 直连各环境」的唯一事实源：当任务需要更强的环境操作能力（查数据库、调内部接口、观测日志等）时，**按此处的定义建立 MCP 连接**，而不是让用户手动搬运信息。

## 文件

| 文件 | 环境 | 默认权限 |
|---|---|---|
| [dev.yaml](dev.yaml) | 开发（本地/联调） | 读写 |
| [test.yaml](test.yaml) | 测试 | 读写 |
| [prod.yaml](prod.yaml) | 生产 | **只读**；任何写操作需用户明确批准 |

## Schema

```yaml
env: dev                          # 环境标识（与文件名一致）
description: …                    # 一句话说明
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
- 环境的**搭建**在 `workspace/env-stack/`，本目录只管**连接**。
- 新增环境（如 staging）：新建 `<env>.yaml` 并在此表登记。
