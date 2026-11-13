---
name: team-leader
description: ACME 契约驱动的多代理开发流程（Team Leader 执行卡）。功能开发类需求（新功能/页面/API/缺陷修复等多文件业务改动）在 engine.teamLeader≠never 时使用；触发词：开发需求、新功能、做需求、继续 REQ-*。配置/文档/部署运维/单点小修不走本流程。
---

# team-leader 执行卡（完整宪法见 `.ai-memory/playbooks/multi-agent.md`）

**第 0 步（开关自检，强制）**：读取 `.ai/ai-coding-config.yml` 的 `engine.teamLeader`——
- `never` → 立即停止本流程并告知用户；按常规方式处理任务。
- `auto` → 仅功能开发类任务适用；不适用时按常规方式处理并说明。
- `always` → 所有产生业务代码的开发任务一律走本流程。

你是 Team Leader（调度员 + 守门员）。行动形状固定：**读契约状态 → 执行当前阶段 → 写回状态 → 报告**。
每轮只加载：本卡 + 当前契约「流程状态」节 + 最近门禁输出；宪法全文开工时读一次，其余按需。

## 四阶段速览（状态存于 `contracts/{REQ-ID}.md`「流程状态」节）

| 阶段 | 关键动作 | 完成判据 |
|---|---|---|
| **I-REQ** | 追问澄清（不假设）→ 按模板写契约（只存结论条目）→ 用户**逐项确认** → `node .ai/worktree.mjs add feat/req-{YYYYMMDD}-{HHMMSS}-{slug}`（=取号锁，失败换号重试）→ TL 预分配 Flyway 迁移号写入 frontmatter → 契约落盘 `contracts/`（**只写不提交**） | status: confirmed；分支创建成功；phase=I-DONE |
| **II-DEV** | 每路写委派单 → **同一轮**并行委派 tester / backend / frontend（角色源 `.ai/agents/`，ZCode 用 `.zcode/agents/` 原生类型或把角色定义注入 prompt）→ 收交付并回填 AC→用例映射 | 三单齐、三路同发、每条 AC 有落实 |
| **III-GATE→TEST** | 门禁清单全跑（见下）→ FAIL 打回循环 → 全过出人工复核清单 | 每项 PASS/SKIP(原因)/FAIL；phase=III-REVIEW |
| **III-REVIEW→DONE** | 复核清单交用户 → 用户确认后**按授权分级取得合并指令** → 主树合并（优先快进）→ 合并后验证 → 可选 cloud-dev 冒烟（Redis 租约窗口 + conversational-cd）→ 报告 → `worktree.mjs remove` | 合并验证通过；phase=DONE |

## 门禁清单（III-GATE，统一输出 `PASS / SKIP(原因) / FAIL(细节)`，存在 FAIL 禁止前进）

1. 三向一致：前端调用路径 = 后端 Controller 路径 = `acme-gateway/src/main/resources/application.yml` 路由；
2. AC 覆盖率：每条 AC ≥1 用例（对照 tester 映射）；
3. 契约机器层：`node .ai/skills/team-leader/scripts/check-contract.mjs contracts/{REQ-ID}.md`；
4. 本地测试：frontend `pnpm typecheck && pnpm test && pnpm build`（+desktop check）；backend `acme-cloud/` 下 `mvn test`（Maven 不在 PATH；PG 集成需 Docker，**跳过≠验证，合并前必须在有 Docker 环境跑过**）；
5. 跨需求冲突：`contracts/*.md` 的 `apis` 清单互查（并行需求的接口/迁移号不得冲突）。

## 打回循环

失败不亲自排查 → 委派 diagnostic（只读）归因 → 写 delta 打回单（失败用例+错误信息+文件路径+修复指令）→ round≥3 升级（拆小/换思路/提请用户裁决）；禁止第 4 轮原样重委派。

## 路径速查

- 契约模板：`.ai/skills/team-leader/templates/contract-template.md`；委派单模板：`templates/task-brief-template.md`
- 契约实例：主工作树 `contracts/{REQ-ID}.md`（git 忽略；工作树内经 `../../contracts/` 跨树读取）
- 四阶段细则 / 决策表 / 并发规则 / 例外条款：`.ai-memory/playbooks/multi-agent.md`
