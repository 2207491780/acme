# 前后端分层 AI-Coding 文件定稿：前端补齐 scoped 规则，澄清派生例外

> 源在 `.ai/scopes/workspace/apps/{backend,frontend}/AGENTS.md`，派生到仓库同路径 `AGENTS.md` + 同级 `CLAUDE.md`（就近生效）。
> 分层用法见 `.ai/scopes/SCOPED-RULES.md`；本记录补充「为何前端此时补齐」与「APPS.md 禁放措辞的派生例外」。

## 背景

用户要求完善前后端的 AI-Coding 文件。调研：后端已有 scoped `AGENTS.md`（聚焦 Flex 数据访问/测试/构建），**前端完全缺失**；
且 `workspace/apps/APPS.md` 旧措辞「禁止在应用目录内放置 AI 工具配置文件（如 CLAUDE.md）」与 `.ai/scopes/` 派生系统直接矛盾——
后端目录早已有派生的 `AGENTS.md`/`CLAUDE.md`。前端 `UI-SPEC.md` 已是详尽的可执行设计契约，无需另建手册。

## 决定

1. **新建前端 scoped 源** `.ai/scopes/workspace/apps/frontend/AGENTS.md`，镜像后端结构、短小聚焦目录专属约定：
   monorepo 结构与三端复用边界、`UI-SPEC.md` 与 `@acme/core` 令牌契约、改令牌后 `pnpm -r test && pnpm ui:guide` 验证流、
   测试（Vitest / node:test）与门禁命令、决策/naming/`FRONTEND.md` 指针。公共规范不重复（仍在全局 `.ai/rules/`）。
2. **完善后端 scoped 源**：结构补 `acme-common 公共池`；新增 `*-api`/`*-service` 契约与分层顺序、`BACKEND.md`/naming 指针，与前端对齐。保留既有 Flex/测试聚焦，最小改动。
3. **澄清 APPS.md 禁放措辞**：禁的是**手放**工具配置（如 `.cursor/rules/`）；由 `.ai/scopes/` 派生的同路径 `AGENTS.md`/`CLAUDE.md` 属允许（就近生效、补充而非覆盖全局）。消除与派生系统的矛盾。
4. **引用而非新建**：前端详细口径指向既有 `UI-SPEC.md` 与三份技术栈决策，不另建前端 playbook，避免重复。

## 被否的选项

- **为前端新建 playbook（如 ui-assembly.md）**：否决——`UI-SPEC.md` 已是可执行契约，再建手册重复维护；scope 指针引用即可。
- **把前端约定写进全局 `.ai/rules/`**：否决——属目录专属，应就近生效（scopes）；全局只放公共约束，且 RULES.md 8KB 预算紧张。
- **保留 APPS.md 原「禁止 CLAUDE.md」措辞**：否决——与 `.ai/scopes/` 派生系统及后端既有派生 `CLAUDE.md` 矛盾，会误导 AI 拒建/误删分层文件。
- **大幅扩写后端 scope**：否决——违背最小改动；仅补全缺失层与导航指针，其余既有聚焦内容不动。