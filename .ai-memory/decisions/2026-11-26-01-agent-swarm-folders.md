# 子代理公共池改为蜂群（swarm）文件夹体系，触发开关 key 化为蜂群名

取代 `2026-11-14-19-multi-agent-contract-workflow.md` 中关于 `engine.teamLeader` 键的部分（四阶段 / 门禁 / 打回循环 / 并发等其余结论继续有效，原文不改写）。

日期：2026-11-26

## 背景

`.ai/agents/` 长期平铺多个子代理文件，无法表达「哪些角色支撑同一功能」的边界，也没有蜂群级 leader 与独立触发开关。原 `engine.teamLeader: auto|always|never` 是单一全局键，隐含「只有一个多代理流程」，与未来多蜂群并存冲突。用户要求把平铺体系改造为蜂群文件夹体系：每个文件夹 = 支撑一个功能的蜂群，每个蜂群有 leader 负责核心调度，触发模式按蜂群名配置。

## 决定

1. **目录结构**：`.ai/agents/` 下除 `AGENT-POOL.md`（唯一保留的说明文件，不派生）外只允许文件夹；每个子目录 = 一个蜂群。现有 5 个子代理用 `git mv` 收进蜂群 `ai-coding-dev/`：`ai-config-editor.md` / `backend-dev.md` / `diagnostic.md` / `frontend-dev.md` / `tester.md`（该蜂群支撑 ai-coding 开发）。
2. **leader**：每个蜂群必须有一个 leader 负责核心调度，命名约定 `<蜂群名>-leader.md`，避免未来多蜂群重名；本蜂群为 `.ai/agents/ai-coding-dev/ai-coding-dev-leader.md`。角色由 `.ai/skills/team-leader/SKILL.md` 与 `.ai-memory/playbooks/multi-agent.md` 提炼：四阶段、契约唯一协调点、委派纪律、门禁 `PASS/SKIP/FAIL`、打回循环（round≥3 升级）、git/部署/权威测试由主线程做；**不发明新流程**，细则回查执行卡与宪法。
3. **触发配置 key 化**：删除 `engine.teamLeader`，在全局唯一源 `.ai/ai-coding-config.yml` 的 `engine` 段改为 key = 蜂群名、value = `auto|always|never`；本仓库设 `ai-coding-dev: auto`。`auto`=功能开发类任务自动触发；`always`=所有会产出业务代码的开发任务；`never`=停用勿主动调用；蜂群键缺省视为 `never` 且**不注入触发段**（与旧缺省仍注入「停用」文案不同）。sync 用 `.ai/agents/` 子目录名集合校验键名，未知键报错；按蜂群生成触发段（编号从 5 递增），文案含 `engine.<蜂群名>: <模式>`、蜂群名、leader 路径、手册与 `/req`，注入根入口存根与 @import 去重存根，均不占 RULES.md 预算。
4. **扁平派生**：源支持嵌套 `.ai/agents/<蜂群名>/<agent>.md`，派生到各工具目录仍为 `<工具>/agents/<agent>.md`（`flat: true` 输出取 basename），保持工具原生发现能力；label / footer / transform 使用完整源 rel；`destinations` 登记簿仍检测不同蜂群的同名 agent 冲突；孤儿检测逻辑不变。POOLS 的 agents 项与 Trae / CodeBuddy 的 agents mirrorPools 均启用。
5. **联动更新**：`AGENT-POOL.md`、`ARCHITECTURE.md`、config 注释、`SKILL.md`、`/req` 命令及其 ZCode 指针、五个 ZCode 存根 + 新增 leader 存根、multi-agent 手册、PLAYBOOK-GUIDE、ai-config 手册、`GUIDE.md`、`PROMPTS.md`、`ZCODE.md` 同步改口径；`.ai/tests/sync.test.mjs` 覆盖三档注入、蜂群键枚举校验、未知键报错、缺省停用、扁平派生与重名冲突。
6. **引擎强制校验**：上述两条布局约束由 `sync.mjs` 的 `listSwarmNames()` 在配置加载前强制——根级只允许说明文件 + 文件夹、每个蜂群必须有 `<蜂群名>-leader.md` 普通文件（不跟随符号链接，错误带具体路径）；蜂群用途描述只登记在 `AGENT-POOL.md`，触发段一律泛化，不写蜂群专属用途，避免新增蜂群时误标。

## 被否的选项

- **保留平铺文件 + 命名前缀表达归属**：否决——前缀只解决命名，不能表达蜂群边界、leader 与独立触发，未来多蜂群仍会混杂；文件夹是天然边界。
- **在每个蜂群文件夹内另建触发配置文件**：否决——多源必然漂移，且 sync 需多处读取；触发模式统一放全局唯一源 `ai-coding-config.yml`。
- **派生也保留蜂群嵌套目录（`<工具>/agents/<蜂群>/<agent>.md`）**：否决——各工具原生按 `<工具>/agents/*.md` 发现子代理，嵌套会导致不被识别或需逐工具改配置；扁平输出 + 完整溯源尾注兼顾发现能力与可追溯。
- **用嵌套结构 `engine.<蜂群名>.mode` 配置**：否决——引擎解析器仅支持两层标量映射，且 key 直接即蜂群名更直观；固定键 + 动态蜂群键的单层 `engine` 足够表达。
- **为 never 档也注入一条「停用」触发段（沿用旧缺省行为）**：否决——缺省即未启用时注入停用声明只会污染入口存根并误导工具；改为缺省不注入、显式 never 才注入一条停用提示。
