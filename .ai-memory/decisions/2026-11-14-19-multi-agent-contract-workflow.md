# 引入契约驱动的多代理开发工作流（team-leader 四阶段）并设引擎触发开关

日期：2026-11-14

## 背景

参考外部《契约驱动的多 Agent 开发工作流（SwarmForge 精简版）》引入多代理协作开发：契约当接口、Agent 当工人、Leader 只当调度员与守门员。该说明书假设手写根 `AGENTS.md` 宪法 + 根目录 `agents.json` + bash/python 校验脚本，与本仓库「`.ai/` 唯一事实源 + sync.mjs 派生引擎 + 分层规则」架构冲突。经逐项决策（用户批复）完成适配，共 7 项：① 触发机制做成引擎开关（用户提出：`ai-coding-config.yml` 增加 team-leader 配置项，自动/总是/从不三档，严格按档触发）；② tester 边界=契约+装配手册+一个既有范本；③ 分支名携带需求号 `feat/req-{YYYYMMDD}-{HHMMSS}-{slug}`（取号锁）；④ 契约 DONE 后原地保留；⑤ 部署冒烟含后端改动默认开、纯前端 SKIP 列报；⑥ ZCode 建本地镜像；⑦ 镜像形态改为引擎生成的引用存根（用户提出：避免 `.zcode/` 下重复声明，改为引用 `.ai/` 公共源）。

## 决定

1. **流程骨架**：四阶段（需求对话→三路并行委派→门禁测试→复核合并）+ 打回循环（diagnostic 归因，round≥3 升级）+ `[GATE]` 协议，全部保留；落地载体：宪法全文 `.ai-memory/playbooks/multi-agent.md`，执行卡 `.ai/skills/team-leader/SKILL.md`（含开关自检），委派单/契约模板随技能包，门禁脚本 `scripts/check-contract.mjs`（Node 重写，保持 `[GATE]` 协议行）。
2. **引擎开关**：`engine.teamLeader: auto|always|never`，缺省 `never`（缺省即现状：不启用）；sync.mjs 校验枚举并把档位触发段注入**所有入口存根**（根部四入口 + Copilot/Trae/Qoder/Cursor），存根不占 RULES.md 预算；技能与命令内置开关自检兜底。本仓库初始值 `auto`。
3. **角色**：四角色进 `.ai/agents/` 公共池（frontend-dev/backend-dev/tester/diagnostic），派生到 .claude/.iflow/.gemini；tester 无 Bash（只写不跑）、diagnostic 纯只读工具面；ZCode 由 TL 注入角色定义或用原生存根。
4. **取号与分支**：需求号 `REQ-YYYYMMDD-HHMMSS`（秒级时间戳防撞）；分支 `feat/req-{YYYYMMDD}-{HHMMSS}-{slug}`，`worktree.mjs add` 原子失败=换号，与工作树纪律（默认工作树）合并为一；Flyway 迁移号由 TL 预分配进契约 frontmatter。
5. **契约落盘**：主工作树根级 `contracts/`（内容 git 忽略 + README 锚定，仿 `.ai-tmp/` 模式）；只写不提交；DONE 后原地保留；跨需求 `apis`/`migrations` 冲突扫描直接扫该目录。
6. **并发口径**：后端集成测试用 Testcontainers 自起容器（flex-test-assembly 口径）天然隔离，无需租约；Redis 租约收窄为「cloud-dev 部署冒烟窗口」串行化（`lock:deploy-smoke` NX EX 3600 + 心跳）。
7. **ZCode 镜像**：`.ai/tools/zcode/`（入库源）→ 派生引用存根到 `.zcode/agents|commands/`（git 忽略）：frontmatter 仅注册元数据，正文一行指针指向 `.ai/` 公共源，子代理 spawn 后自读源文件；TOOLS 数组加 `zcode` 一行，孤儿检测自然覆盖。
8. **授权衔接**：流程内合并/推送仍按「AI 操作授权分级」取得用户显式指令；复核清单确认 ≠ 自动授权。

## 被否的选项

- **手写根 AGENTS.md 宪法（说明书原样）**：否决——根 AGENTS.md 是 `.ai/RULES.md` 派生存根，手改违反铁律；宪法全文移入手册，规则零新增。
- **触发条款写入 `.ai/rules/`**：否决——RULES.md 余量仅 162 字节且 ADR 2026-11-13-01 明确「优先瘦身不放宽」；引擎开关注入存根既零预算成本又三档可控。
- **纯 prompt 触发（无引擎开关）**：否决——触发语义需全工具一致且用户可控，prompt 约定会漂移。
- **tester 全盲只读契约（说明书原样）**：否决——ACME 测试装配坑多（H2/PG 方言隔离、reuseForks 等 8 条口径），全盲写用例必翻车；独立性核心（不读实现、只写不跑）保留。
- **维持 `feat/<简述>`（不带需求号）**：否决——取号锁退化为查重后取号（竞态窗口）；REQ-ID 入分支名保留原子语义。
- **每需求必部署冒烟**：否决——纯前端/文档改动付 6-8 分钟部署无益；按改动性质分档。
- **ZCode 角色完整拷贝**：否决（用户指正）——双份维护违反单一事实源精神；改引用存根，仅 frontmatter 注册元数据重复（工具格式固有）。
- **ZCode 镜像用 symlink**：否决——Windows 符号链接需特权、跨机器脆弱；沿用入口层 `entryMode: stub` 同款取舍。
- **契约入库提交**：否决——契约是流程现场非仓库资产，入库会污染策略分支并触发镜像门禁噪音；git 忽略 + README 锚定。
- **DONE 后归档/删除契约**：否决——保留现场便于跨会话追溯（委派历史/门禁台账）；AC 已沉淀在代码与测试，丢失风险可接受。
