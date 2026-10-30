# 开发命令手册（dev-commands）

> 规则 `.ai/rules/project.md` 只保留高频命令，本手册为完整版。

## 后端（`workspace/apps/backend`）

```bash
mvn -N install -f acme-parent/pom.xml                  # 冷启动：先装 parent（Backend.md 6.4.3）
mvn -f acme-cloud/pom.xml clean install                # 全量构建
mvn -pl <module> -am -f acme-cloud/pom.xml install     # 增量：变更模块 + 上游
mvn -f acme-cloud/pom.xml test                         # 全量测试
```

- 独立 SQL 初始化（仅开发）：`psql -h localhost -U $PG_USER -d $PG_DB -f backend/dev-init/00-platform-schemas.sql`

## 前端（`workspace/apps/frontend/web-admin`）

```bash
pnpm install                 # 依赖（锁定 pnpm-lock.yaml）
pnpm dev                     # 开发
pnpm run check               # 循环依赖 + 依赖 + 类型 + 拼写
pnpm run test:unit           # vitest
pnpm run build               # 生产构建（turbo）
pnpm run lint                # oxlint / oxfmt / stylelint
```

## 环境

```bash
cp env-stack/docker/dev/.env.example env-stack/docker/dev/.env          # 首次
docker compose -f env-stack/docker/dev/compose.dev.yml up -d            # 起栈
docker compose -f env-stack/docker/dev/compose.dev.yml ps               # 须健康
```

## 仓库级

```bash
node .ai/sync.mjs && node .ai/sync.mjs --check   # AI 配置派生与校验
```
