# CodeBuddy 派生机制对齐官方配置方案（@ 导入入口、公共池引用存根）

日期：2026-11-17
背景关联：`2026-11-17-01-ai-config-governance.md`（CodeBuddy 以 localOnly 键纳管，公共池全量镜像——本记录**部分取代**其「全量镜像」做法）、`2026-11-17-02-qoder-official-alignment.md`（Qoder 同类对齐先例）、`2026-11-14-20-zcode-stub-local-only-check.md`（localOnly 语义）。

## 背景

1. **官方配置方案已核实**（docs.codebuddy.ai / codebuddy.cn「.codebuddy 目录结构说明」「管理 CodeBuddy 的记忆」，CLI 版本口径至 v2.184.0，核实于 2026-11-17）：
   - 记忆机制支持 **`@path` import**（相对/绝对路径、递归导入、深度 5、代码块内不解析）；项目 `CODEBUDDY.md` 与 `.codebuddy/CODEBUDDY.md` 等效，存在时优先于 `AGENTS.md`。
   - 项目级 `.codebuddy/` 官方目录：`agents/<name>.md`（frontmatter：name/description/tools/model）、`skills/<name>/SKILL.md`（可含辅助文件）、`commands/<name>.md`（子目录映射冒号命令名）、`rules/*.md`（frontmatter `enabled`/`alwaysApply`/`paths`）、`settings.json`/`settings.local.json`；官方建议这些目录**提交入库**共享团队，优先级项目级 > 用户级（同名覆盖）。
   - 官方另有 Auto Memory / Typed Memory（`~/.codebuddy/memories/`）、用户级 `~/.codebuddy/` 目录、CLI `--setting-sources` 等，均属工具自管理面。
2. **旧做法偏离官方且重复声明**：2026-11-17-01 把公共池 agents/commands **全量拷贝**进 `.codebuddy/`（当时否决引用存根的理由是「未验证 CodeBuddy 子代理有运行时回读源文件的等效机制」）。官方文档确认子代理正文即系统提示、由带工具权限的 Agent 执行（frontmatter `tools` 含 Read）——回读源文件的等效机制成立，引用存根可行；且入口 `CODEBUDDY.md` 当时是内联铁律节选的存根，与官方原生 `@` 导入能力重复。

## 决定

1. **入口改官方 `@` 导入**：`LINK_TARGETS` 的 `claudeImport` 泛化为 `atImport`（Claude Code 行为不变）；CodeBuddy 登记为 `atImport: true` + `atImportDedupe: true`——`CODEBUDDY.md` 用 `@.ai/RULES.md` 内联完整规范，正文**不重复铁律节选**，仅保留一句自然语言兜底指针与引擎注入的多代理流程开关（后者不属于 RULES.md，无重复）。
2. **公共池改引用存根**：`mirrorPools` 支持 `{ src, transform }` 条目；`.codebuddy/agents|skills|commands/` 用 `makePoolRefStub`（由 Qoder 的 `makeQoderStub` 泛化，Qoder 输出逐字不变）——frontmatter 原样保留供官方注册，正文仅指针指向 `.ai/` 池源，磁盘零重复；技能辅助文件（非 .md）照旧全量拷贝；**新增 skills 池**（官方原生支持 `.codebuddy/skills/`）。
3. **维持 localOnly（与官方差异，有意适配）**：官方建议 `.codebuddy/` 提交入库；本仓库保持 git 忽略——目录与工具本机运行数据（`teams/`）混居、CI 全新检出无此目录，`--check` 缺失跳过/存在防漂移语义不变。
4. **不设 `.codebuddy/rules/` 存根**：官方 rules 与记忆是两条加载通道；本仓库规范正文已由 `@.ai/RULES.md` 导入，再加 rules 指针即双份加载，违背「不重复声明」。
5. 回归锁定：`sync.test.mjs` 的 `should_mirror_pools_into_local_only_codebuddy_target` 改写为 `should_derive_codebuddy_entries_as_official_aligned_references`（入口 @ 导入且无铁律节选 + 三池 frontmatter 保留/正文零重复/合成头 + 派生标记 + localOnly 三段语义 + --check 幂等）。

## 兼容矩阵（官方释义 vs 本仓库适配，2026-11-17 核实）

| 能力 | 官方支持情况 | 本仓库适配 |
|---|---|---|
| 记忆 `CODEBUDDY.md` | ✅ 根目录或 `.codebuddy/` 内等效；支持 `@path` 导入（递归 ≤5 层）；存在时优先于 `AGENTS.md`；子目录记忆按需动态加载 | 根 `CODEBUDDY.md` 为 `@.ai/RULES.md` 导入存根 + 兜底指针 + 多代理开关（不重复铁律节选）；导入需新会话冒烟验证内联生效 |
| Rules `.codebuddy/rules/*.md` | ✅ 递归加载（仅 cwd，不向上）；frontmatter `enabled`/`alwaysApply`/`paths`（glob） | **不派生**——规范正文走 `@` 导入，另设 rules 指针会双份加载；需要时在 `.ai/tools/codebuddy/` 建源 |
| Agents `.codebuddy/agents/<name>.md` | ✅ frontmatter name/description/tools/model；项目级 > 用户级，同名覆盖；正文为系统提示（Agent 可用工具回读源文件） | 引用存根（frontmatter 保留、正文指针指向 `.ai/agents/` 源）；取代 2026-11-17-01 全量镜像 |
| Skills `.codebuddy/skills/<name>/SKILL.md` | ✅ 官方项目级技能，可含辅助文件 | **新增派生**：SKILL.md 引用存根；scripts/templates 等辅助文件全量拷贝（渐进披露本地可用） |
| Commands `.codebuddy/commands/<name>.md` | ✅ 文件名即命令名，子目录映射冒号名；frontmatter `$ARGUMENTS`/allowed-tools | 引用存根（`/req` 可用，执行时回读 `.ai/commands/req.md`） |
| Settings / MCP | ✅ `.codebuddy/settings.json`（共享）/`settings.local.json`（本机）；项目级 MCP 在根 `.mcp.json` | **当前未配置**，无对应源；需要时在 `.ai/tools/codebuddy/` 建源派生。仓库 git 钩子（`.ai/hooks/`）是另一机制 |
| 版本控制 | 官方建议项目 `.codebuddy/` 提交共享 | ⚠️ **有意偏离**：git 忽略、localOnly（与 `teams/` 本机运行数据混居、CI 无此目录）；`--check` 缺失跳过、存在必须一致 |
| 记忆（auto/typed） | `~/.codebuddy/memories/` 应用自管理 | 不参与仓库派生；仓库知识落盘 `.ai-memory/` |

## 被否的选项

- **公共池维持全量镜像**：否决——官方确认子代理/命令正文由带工具权限的 Agent 执行，引用存根等效生效；全量拷贝是磁盘重复 + 双份漂移面，与用户「能用引用则用引用」要求冲突（Qoder 先例同取舍，ADR 2026-11-17-02）。
- **`CODEBUDDY.md` 保持内联铁律节选**：否决——官方 `@` 导入会内联 `.ai/RULES.md` 全文，入口再留节选即同会话重复声明；Qoder 去重同理由。
- **按官方建议取消 `.codebuddy/` 的 git 忽略并入库**：否决——目录含 `teams/` 等本机运行数据（官方 runtime 数据不入库的口径只覆盖 `~/.codebuddy/` 项目外目录，项目内运行数据边界官方未定义）；CI/协作者会被强加本机状态。维持 localOnly，差异如实记录于本矩阵。
- **入口继续走「回退读 AGENTS.md」的双保险文案**：否决——官方明示 `CODEBUDDY.md` 存在时优先于 `AGENTS.md`，不回退；`@` 导入是官方一等机制，比依赖回退更可靠。
