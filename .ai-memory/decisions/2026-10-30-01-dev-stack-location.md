# ADR-2026-10-30-01：开发依赖栈归属 `workspace/env-stack/docker/dev/`

日期：2026-10-30　分支：acme-ark　状态：已落地

## 背景

开发中间件栈（PostgreSQL / Redis / Nacos / Kafka / RocketMQ）此前在 `.ai-tmp/.dev-stack/`。
该位置有两处不合规：

1. `.ai-tmp/` 按根 `.gitignore` 整目录忽略（仅 `ABOUT.md` 入库），编排文件无法入库、无法协作。
2. 目录名带前导点且位于 `.ai-tmp/` 下，与项目既有约定冲突——`ENV-STACK.md` 规定编排按环境分层于
   `env-stack/docker/<env>/`，`ENV-GUIDE.md` 规定「搭建归 env-stack、连接归 .ai/env」。

## 决定

迁至 `workspace/env-stack/docker/dev/`，入口文件命名 `compose.dev.yml`（对齐 `ENV-STACK.md` 的
`compose.dev.yml / compose.test.yml` 约定），组件一目录一个，`.env.example` 随栈而居（与 `.env` 同目录）。

同步动作：

- 全部 `.dev-stack/` 路径引用改为 `env-stack/docker/dev/`；README 命令统一
  `docker compose -f env-stack/docker/dev/compose.dev.yml ...`（在仓库根执行）。
- `.ai/env/dev.yaml` 从 TODO 骨架补全为可执行的 MCP 连接定义（PG/Redis/Kafka/Nacos 四条），
  凭证一律 `${VAR}` 引用，未落明文。
- 修正根 `.gitignore`：原 `.env.*` 连带忽略 `.env.example`，导致模板无法入库；补 `!.env.example` 例外。

## 被否的选项

- **留在 `.ai-tmp/` 只改 gitignore**：否决。`.ai-tmp/` 定位是「AI 临时文件区」，把交付物长期放这里
  与其语义冲突，且每次协作者都要重新发现位置。
- **迁到仓库根 `.dev-stack/`**：否决。违反 `ENV-STACK.md` 的分层约定（`docker/<env>/`），
  且 test 环境落地时会再产生一次迁移。
- **组件 compose 文件名一并改为 `compose.dev.yml`**：否决。组件文件由入口 `include` 引用，
  保持 `docker-compose.yml` 更贴近各组件目录自洽的既有写法；分层信息由所在目录表达。

## 遗留

- 旧 `.ai-tmp/.dev-stack/` 尚未删除（含本地 `.env` 真实凭证，删除属破坏性操作，待用户确认后清理）。
- Kafka MCP 用 `kafka-mcp-server`（注册表可查到的第三方包，官方 `@modelcontextprotocol/server-kafka`
  不存在）；其 broker 环境变量名待起实例确认后回填 dev.yaml。
- Nacos 3.x 无通用 MCP 包，配置/服务读写需本地适配层或 `docker compose exec`。
- RocketMQ 无 MCP server，经 `mqadmin` 操作。
