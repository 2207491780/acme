# backend — 后端根目录

Java 微服务后端。**规范权威文档：[doc/Backend.md](doc/Backend.md)**（18 章：架构、分层、构建、契约、事务、网关、数据等）；本文件只是入口速览。

## 技术栈

Java 25 · Spring Boot 4.1.1 · Spring Cloud 2025.1.3 · Maven 多模块（`revision` = 1.0-SNAPSHOT）。

## 结构

```text
backend/
├── acme-parent/     # 父 POM（版本与依赖管理）
├── acme-cloud/      # 聚合工程
│   ├── acme-common/     # 通用契约与工具
│   ├── acme-framework/  # 技术能力封装（web/缓存/消息…）
│   ├── acme-gateway/    # 网关
│   ├── acme-platform/   # 平台服务（auth/system）
│   └── acme-business/   # 业务服务
├── dev-init/        # 本地初始化 SQL（仅开发）
├── doc/             # Backend.md / Modules.md / Services.md
└── BACKEND.md       # 本文件
```

## 快速开始

构建与测试命令的唯一权威清单见 `.ai-memory/playbooks/dev-commands.md`（要点：冷启动先 `mvn -N install -f acme-parent/pom.xml`，再全量 `mvn -f acme-cloud/pom.xml clean install`）。

环境依赖栈见 `workspace/env-stack/docker/dev/`（PG/Redis/Kafka/RocketMQ/Nacos）。目录级 AI 规则由 `.ai/scopes/` 派生（本目录 `AGENTS.md`）。
