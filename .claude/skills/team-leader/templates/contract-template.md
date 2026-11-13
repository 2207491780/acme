---
requirementId: "REQ-{YYYYMMDD}-{HHMMSS}"
title: "{一句话标题，含业务语义}"
status: draft              # draft | confirmed（用户逐项确认后置 confirmed；未 confirmed 禁止进入 II-DEV）
branchContext:
  baseBranch: acme-foundation
  mergeTarget: acme-foundation
  featureBranch: "feat/req-{YYYYMMDD}-{HHMMSS}-{slug}"   # 取号锁：worktree.mjs add 失败=已占用，换号重试
migrations: {}             # TL 预分配的 Flyway 版本号，键=服务模块名，如 { platform-auth: "V5" }；工人禁自行取号
apis:                      # 机器层路径清单（跨需求冲突扫描/三向核对用；语义看正文 §API），如 "POST /app/v1/auth/tokens"
  - "{METHOD} {path}"
---

# {title}

## 背景
{只写实现者不知道的事实：依赖机制、已实测数据；≤10 行}

## 确认记录（契约的权威解释；只存结论条目，禁存对话原文）
- [{日期}] ① {结论}
- [{日期}] ② {结论}

## API
<!-- 每接口要点 ≤10 行，超出的下沉为 AC；错误码在此列全；业务失败=Result.code≠0（common-core 统一响应体），写明与 HTTP 状态码的关系 -->
### {接口名}（{METHOD} {path}）
- {要点}
- 返回：`Result<{关键字段}>`（`code` 语义、失败码枚举）

## 验收标准（规格核心；AC 是规格+追溯锚点+覆盖率核对单元）
- AC-1 given: {前置} when: {操作} then: {可断言结果} → {用例名，tester 交付后回填}
- AC-2 given: ... when: ... then: ... → {用例名}

## 变更清单（差异契约：只写变更，不重述未变现状）
- 删除：{端点/文件/错误码}
- 数据：{表变更；Flyway 迁移幂等守卫；迁移号见 frontmatter migrations}
- 前端：{页面与改造性质；交互：成功/失败/重推提示；令牌只取 @acme/core}

## 流程状态（TL 每次行动后更新；这是唯一现场，跨会话据此恢复）
- phase: I-REQ            # I-REQ | I-DONE | II-DEV | III-GATE | III-TEST | III-REVIEW | DONE
- round: 0                # 连续修复轮次，满 3 触发升级
- 门禁台账：
  - {门禁名}: PASS / SKIP({原因}) / FAIL({细节})
- 委派历史：
  - {角色}: {交付/打回(round n)}
- 升级记录：

<!-- 派生自 .ai/skills/team-leader/templates/contract-template.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
