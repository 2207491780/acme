# 编码规范

> 所有 AI 工具生成/修改代码时必须遵守。标注 TODO 的部分按团队实际约定补齐。

## 语言与格式

- 后端：Java 25（Spring Boot 4.1.1 / Spring Cloud 2025.1.3），Maven 多模块；规范详见 `workspace/apps/backend/doc/Backend.md`。
- 前端：TypeScript（strict），Vue 3 + Vite；web-admin 基于 vben v5，命令用 pnpm。
- 统一使用项目的格式化与 lint 配置（前端 `pnpm run lint`、后端按 Backend.md），禁止自行调整缩进/引号风格。
- 文件编码 UTF-8（无 BOM），换行符 LF。

## 命名约定

- Java：包全小写点分（`com.acme.platform.auth`）、类 PascalCase、类文件随类名。
- TS/Vue：变量函数小驼峰、组件 PascalCase；文件/目录 kebab-case。
- 常量 UPPER_SNAKE（`MAX_RETRY`）。

## 注释

- 注释解释「为什么」，不解释「是什么」。
- 公开 API 必须有文档注释；内部实现仅在逻辑不直观时注释。
- TODO 注释必须带负责人或 issue 号：`// TODO(name): ...`。

## 错误处理

- 不允许吞异常：捕获后必须处理（日志/降级/向上抛）。
- 错误信息面向使用者可读，不暴露内部实现细节。

## 依赖管理

- **新增依赖前必须说明理由**，禁止引入未在 `.ai/rules/project.md` 技术栈中登记的框架/库。
- 优先使用语言标准库和现有依赖解决问题。

## 测试要求

- 新增/修改业务逻辑必须附带对应测试。
- 测试命名表达意图：`should_<行为>_when_<条件>`。
- 提交前必须跑通全量测试，禁止跳过失败的测试。
