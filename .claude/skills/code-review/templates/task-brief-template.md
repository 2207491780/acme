# 评审委派单 {REVIEW-ID}-{role}

- **任务摘要**：{3-5 行：审什么 + 依据哪份事实清单 + 交付什么；子 Agent 靠此即可开工，不读任何流程文档}
- **评审号**：{REVIEW-YYYYMMDD-HHMMSS}（leader 评审开始时生成；临时脚本目录 `.ai-tmp/code-review/{REVIEW-ID}/{worker}/`）
- **本轮启用集合 / 本角色命中维度 / 未启用角色及原因**：启用集合：{本轮已启用角色列表，最多 7 工人 + 1 裁决员；未启用角色不派单}；本角色命中维度：{按 `review-checklist.md` 节选的 Tier / 维度及 `file:line`}；未启用角色及原因：{列角色 + 无相关调用边 / 无契约变更 / 不在范围}；空缺不算漏审，基线缺口照写
- **输入**：阶段 0 带 `file:line` 的事实清单节：{结构 / 契约 / 调用链 / 运行时 / 规范 / 重复声明与冗余逻辑，按角色挑}；评审范围文档：{相关分层 AGENTS.md、FRONTEND.md/BACKEND.md/UI-SPEC.md}
- **重复/冗余维度（每份委派单必带，确保角色自包含）**：{第 6 类清单节：自定义注解 `@interface` / 跨模块同名类与方法体 / 跨端重复类型与 HTTP 客户端 / 同名 Flyway 迁移（区分独立 location）/ 重复配置键与常量，均带 `file:line`}；检测口径：{**无白名单**扫描，覆盖自定义注解与自定义类型；候选按 **真实重复 / 模块特化 / 探针假阳性** 三类判定并写依据；「bean 名不同」「显式限定符不同」只能排除 Spring bean 冲突，不得据此排除代码重复；同名类须比对方法体、注解元信息（如 `@Target`）、异常口径后才可结案；探针候选由工人复核，leader 不单方面按命名冲突排除}
- **维度节选 + 优先级 + 交给 CI/L1 项**：依据 `review-checklist.md` 按本角色节选：{Tier0 架构/设计/依赖/模块职责（最高，安全与架构重大缺陷一票否决）/ Tier1 业务与正确性 / Tier2 安全·性能·并发·数据库·异常日志·接口RPC MQ·部署 / Tier3 交 CI/L1 的项}；本角色负责的节选维度：{列举}；优先级：{Tier0 最高 / 本角色主要层级}；交给 CI/L1 项：{只抽样复核并标「交 L1」，不表述为 lint 通过、不新增依赖}
- **边界**：只回传结论给 leader；禁读 {对端实现 / 清单外模块 / 范围外下游}；禁写仓库内任何文件；不产生 REQ、不写第二份 md、不写业务代码
- **脚本边界（适用本轮已启用工人，角色池固定 7 人）**：Write/Bash 只能把审核 `.mjs` 写到 `.ai-tmp/code-review/{REVIEW-ID}/{worker}/`，遵守 SKILL「跨平台硬规范」六条，只清理本人子目录、用完经 `run.mjs --cleanup` 删除；`verdict-arbitrator` 纯只读，无 Write/Bash
- **验证级别**：{L0 / L0+L1 / L0+L1+L2（认证/租户/事务/MQ 幂等顺序/双形态/DTO 序列化用 L2）}
- **交付物**：{带 `file:line` 的意见列表（文件:行 | 维度 | 问题 | 证据 | 建议 | origin | 归属）+ L1/L2 命令与输出摘录 + SKIP(原因)}

<!-- 派生自 .ai/skills/code-review/templates/task-brief-template.md（统一管理于 .ai/），请修改源文件后运行 node .ai/sync.mjs -->
