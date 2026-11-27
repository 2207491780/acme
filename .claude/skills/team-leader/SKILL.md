---
name: team-leader
description: ACME 契约驱动的多代理开发流程（ai-coding-dev 蜂群 leader 执行卡）。功能开发类需求（新功能/页面/API/缺陷修复等多文件业务改动）在 engine.ai-coding-dev 为 auto|always 时使用（never 或缺省时停用，manual=仅用户显式要求时进入、/req 仍可用；文案口径 engine.<蜂群名>，本蜂群为 ai-coding-dev）；触发词：开发需求、新功能、做需求、继续 REQ-*。配置/文档/部署运维/单点小修不走本蜂群流程。
---

# team-leader 执行卡（完整宪法见 `.ai-memory/playbooks/multi-agent.md`）

**第 0 步（开关自检，强制）**：读取 `.ai/ai-coding-config.yml` 的 `engine.ai-coding-dev`（即 `engine.<蜂群名>`，本蜂群为 ai-coding-dev）——
- `never` → 立即停止本流程并告知用户；按常规方式处理任务。
- 未配置（缺省）→ 视为停用，停止本流程并按常规方式处理。
- `auto` → 仅功能开发类任务适用；不适用时按常规方式处理并说明。
- `always` → 所有产生业务代码的开发任务一律走本流程。
- `manual` → 不自动触发；仅用户对话显式要求（点名本蜂群或 `/req` 命令）时按本流程执行，否则按常规方式处理。

你是蜂群 leader（调度员 + 守门员；旧称 Team Leader）。行动形状固定：**读契约状态 → 执行当前阶段 → 写回状态 → 报告**。
每轮只加载：本卡 + 当前契约「流程状态」节 + 最近门禁输出；宪法全文开工时读一次，其余按需。

## 四阶段速览（状态存于 `contracts/{REQ-ID}.md`「流程状态」节）

| 阶段 | 关键动作 | 完成判据 |
|---|---|---|
| **I-REQ** | 追问澄清（不假设）→ 按模板写契约（只存结论条目）→ 用户**逐项确认** → `node .ai/worktree.mjs add feat/req-{YYYYMMDD}-{HHMMSS}-{slug}`（=取号锁，失败换号重试）→ TL 预分配 Flyway 迁移号写入 frontmatter → 契约落盘 `contracts/`（**入库**：draft 即提交，confirmed 后状态更新再提交——跨会话/跨机可见，需求号/迁移号占用记录随 git 防撞） | status: confirmed；分支创建成功；phase=I-DONE |
| **II-DEV** | 每路写委派单 → **同一轮**并行委派 tester / backend / frontend（角色源 `.ai/agents/ai-coding-dev/`，ZCode 用 `.zcode/agents/` 原生类型或把角色定义注入 prompt）→ 收交付并回填 AC→用例映射 | 三单齐、三路同发、每条 AC 有落实 |
| **III-GATE→TEST** | 门禁清单全跑（见下）→ FAIL 打回循环 → 全过出人工复核清单 | 每项 PASS/SKIP(原因)/FAIL；phase=III-REVIEW |
| **III-REVIEW→DONE** | 复核清单交用户 → 用户确认后**按授权分级取得合并指令** → 主树合并（优先快进）→ 合并后验证 → 可选 cloud-dev 冒烟（Redis 租约窗口 + conversational-cd）→ 报告 → `worktree.mjs remove` | 合并验证通过；phase=DONE |

## 门禁清单（III-GATE）

五项门禁（1 三向一致 / 2 AC 覆盖率 / 3 契约机器层 / 4 本地测试 / 5 跨需求冲突）——
完整判定细则（脚本命令、判据、PG/Redis 集成口径）唯一权威：`.ai-memory/playbooks/multi-agent.md`「阶段三 门禁与测试」门禁表，本卡不展开（防两处漂移）。
统一输出 `PASS / SKIP(原因) / FAIL(细节)`：存在 FAIL 禁止前进；SKIP 必须列入阶段报告，且「PG/Redis 集成必须跑过」不适用 SKIP 豁免。

## 打回循环

失败不亲自排查 → 委派 diagnostic（只读）归因 → 写 delta 打回单（失败用例+错误信息+文件路径+修复指令）→ round≥3 升级（拆小/换思路/提请用户裁决）；禁止第 4 轮原样重委派。

**打回轮纪律**：优先 SendMessage 续原 worker 会话（免冷启动重读）；门禁复跑仅定向（受影响模块/文件），全量只留终验与合并后；前端并行 flake 先串行复跑判别，不算 FAIL。细则见手册门禁表与打回循环。

## 路径速查

- 契约模板：`.ai/skills/team-leader/templates/contract-template.md`；委派单模板：`templates/task-brief-template.md`
- 契约实例：主工作树 `contracts/{REQ-ID}.md`（已入库；worktree 检出基点之后落盘的新契约经 `../../contracts/` 跨树读最新）
- 四阶段细则 / 决策表 / 并发规则 / 例外条款：`.ai-memory/playbooks/multi-agent.md`

<!-- 派生自 .ai/skills/team-leader/SKILL.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
