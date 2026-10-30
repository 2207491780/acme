# 项目上下文

> 详细命令与结构见 `.ai-memory/playbooks/dev-commands.md`。

## 项目简介

ACME：AI-Coding 工程模板 + 实际业务仓。业务代码在 `workspace/`：后端 Spring Cloud 微服务、前端 vben v5 中后台。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 25 · Spring Boot 4.1.1 · Spring Cloud 2025.1.3 · Maven 多模块 |
| 前端 | Vue 3 · Vite · TS · pnpm 11 · turbo（web-admin = vben v5）；其余前端占位 |
| 环境 | Docker Compose 依赖栈（PG / Redis / Kafka / RocketMQ / Nacos） |

## 目录结构

```text
acme/{.ai,.ai-memory,.ai-tmp,workspace,docs}
workspace/apps/backend    # 后端，规范权威 doc/Backend.md（目录说明 BACKEND.md）
workspace/apps/frontend   # 前端多项目，web-admin 活跃（见 web-admin/AGENTS.md）
workspace/env-stack       # 环境搭建（见 ENV-STACK.md）
```

## 环境与 MCP 连接

- 环境三态与 MCP 直连：`.ai/env/{dev,test,prod}.yaml`（dev/test 读写，**prod 只读**；Schema 见 `.ai/env/ENV-GUIDE.md`，严禁明文密钥）。
- 环境搭建 `workspace/env-stack/`；应用代码 `workspace/apps/`。

## 常用命令

- 后端：`mvn -N install -f acme-parent/pom.xml` → `mvn -f acme-cloud/pom.xml clean install`
- 前端：`cd workspace/apps/frontend/web-admin && pnpm install && pnpm dev`
- 环境：`docker compose -f workspace/env-stack/docker/dev/compose.dev.yml up -d`
