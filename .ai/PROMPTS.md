# 让 AI 自己维护这套结构 —— 提示词案例库

> 复制即用，适用于全部兼容工具。配套：[GUIDE.md](GUIDE.md)（人侧操作）、[ai-config.md](../.ai-memory/playbooks/ai-config.md)（AI 侧流程）。

## 为什么可以放手让 AI 维护（三层保障）

| 层 | 机制 | 对 AI 意味着什么 |
|---|---|---|
| **知道规矩** | 所有工具入口（8 个）都注入了铁律：只改 `.ai/`、必跑 `sync`、必过 `--check` | AI 一开工就「读过」规矩，无需在提示词里重复 |
| **照章办事** | `.ai-memory/playbooks/ai-config.md` 把维护动作固化为五步确定性流程（定位 → 只改源 → 派生 → 校验 → 汇报） | 动作可预期、可复现 |
| **错了被拦** | sync 幂等；`--check` 逐字节校验 + 孤儿检测；pre-commit 钩子与 CI 双重拦截 | 即使 AI 出错，漂移也进不了仓库 |

因此**提示词只需表达意图，不需要教流程**——机制会把意图约束到正确路径上。

## 通用模板

所有工具通用的最简形式：

> 「<做什么>」。按 `.ai-memory/playbooks/ai-config.md` 的流程执行。

Claude Code / iFlow / Gemini 有原生子代理与技能，后半句可省（自动接管）：

> 用 ai-config-editor：<做什么>。

**验收三件套**（AI 交付时必须给出，缺一视为未完成，可直接打回）：

1. 源文件改动清单（应全部位于 `.ai/` 下）
2. 派生变化清单（`node .ai/sync.mjs` 输出）
3. `node .ai/sync.mjs --check` 全绿的输出原文

> 💡 一个重要习惯：**说意图，不说文件**。不要说「改一下 CLAUDE.md」——正确意图是「把提交规范改成 72 字」；AI 负责翻译成「改 `.ai/rules/git-workflow.md` → sync」。如果你直接点名派生文件，反而是在诱导 AI 违规。

---

## 提示词案例

### 一、规范修改（最常用）

| # | 提示词 | 预期 AI 行为 |
|---|---|---|
| 1 | 把提交信息 subject 上限从 50 字改成 72 字 | 改 `.ai/rules/git-workflow.md` 对应行 → sync → 全部 8 个入口更新 |
| 2 | 编码规范补充：所有公开函数必须有 JSDoc，内部函数不强制 | 只改 `.ai/rules/coding-standards.md` 的注释小节 |
| 3 | 命名约定给 Go 后端开例外：包名全小写、导出用 PascalCase | 在命名表中增加语言例外行，不改其他语言约定 |
| 4 | 分支模型改成 trunk-based：main 直接小步提交，特性分支存活不超过 3 天 | 改 `.ai/rules/git-workflow.md` 分支模型表 + 相关禁止事项 |

### 二、结构演进

| # | 提示词 | 预期 AI 行为 |
|---|---|---|
| 5 | 给 packages/api 加目录级规范：错误统一抛 ApiError 带 code，handler 不写 try/catch | 新建 `.ai/scopes/packages/api/AGENTS.md` → sync 自动生成同路径副本与同级 `CLAUDE.md` 存根 |
| 6 | 删掉 packages/api 的目录级规范 | 删 `.ai/scopes/packages/api/AGENTS.md`；**并手动清理** `packages/api/AGENTS.md` 与 `packages/api/CLAUDE.md`（分层派生文件不在孤儿检测范围，见 `.ai/scopes/SCOPED-RULES.md`） |
| 7 | 把测试要求从编码规范里拆成独立的 testing.md | 源文件移动/拆分到 `.ai/rules/`（文件名排序即拼接序）→ sync |

### 三、能力扩展（子代理 / 技能 / 命令）

| # | 提示词 | 预期 AI 行为 |
|---|---|---|
| 8 | 加一个 code-reviewer 子代理：专职评审，关注正确性、边界、测试覆盖，输出按严重度排序 | 新建 `.ai/agents/code-reviewer.md`（标准 frontmatter）→ 派生到 `.claude/.iflow/.gemini` 三处 |
| 9 | 做一个 release-checklist 技能：对齐 CHANGELOG、全量测试、打 tag | 新建 `.ai/skills/release-checklist/SKILL.md` → 派生到 Claude 技能与 iFlow workflow |
| 10 | 加 /deploy 命令：先测试、再构建 $ARGUMENTS 目标、部署测试环境；Gemini 也要能用 | 新建 `.ai/commands/deploy.md` **并主动补** `.ai/tools/gemini/commands/deploy.toml`（TOML 转换是 playbook 已知事项） |
| 11 | Cursor 里改 SVG 总是破坏主题色，加一条只在触及 SVG 时生效的规则 | 新建 `.ai/tools/cursor/rules/svg.mdc`（`globs: "**/*.svg"`） |

### 四、工具接入

| # | 提示词 | 预期 AI 行为 |
|---|---|---|
| 12 | 团队要新增 Windsurf 支持 | 读 `.ai/ARCHITECTURE.md` 新增工具流程 → 改 `sync.mjs` 的 `STUB_TARGETS` → 建 `.ai/tools/windsurf/WINDSURF.md` → sync |
| 13 | 我们还用 Kimi Code，需要做什么？ | 正确回答：**什么都不用做**，它读 `AGENTS.md`，已天然覆盖（考验 AI 是否真懂体系） |

### 五、体检与治理

| # | 提示词 | 预期 AI 行为 |
|---|---|---|
| 14 | 给 AI 配置体系做一次体检 | 跑 `--check`；扫描孤儿；审阅 `.ai/rules/` 与实际项目是否脱节；输出健康报告 |
| 15 | 审计 .ai/rules 里所有 TODO，列出还没定型的决策清单，附建议 | 只读操作：汇总 TODO → 分类 → 建议，**不改动**（待确认后再下执行指令） |
| 16 | RULES.md 里「测试要求」在两个章节重复了，去重 | 定位重复源头在 `.ai/rules/` 的两个文件 → 合并到一处 → sync |

### 六、元维护（让 AI 演进体系本身）

| # | 提示词 | 预期 AI 行为 |
|---|---|---|
| 17 | playbooks 里加一个 db-migration 手册：数据库变更的检查流程，并在规则里挂上触发条款 | 新建 `.ai-memory/playbooks/db-migration.md` + 在 `.ai/rules/ai-guidelines.md` 加触发（手册不会自动被知道，必须挂规则） |
| 18 | 给 sync 引擎加 --prune 选项：自动删除孤儿派生文件而不是只报告 | 直接修改 `.ai/sync.mjs`（它本身就在 `.ai/` 内，属合法修改）→ 自测幂等 |

### 七、过程性内容落盘（把会话结论变成仓库资产）

| # | 提示词 | 预期 AI 行为 |
|---|---|---|
| 19 | 刚才讨论的「测试前要不要跑 lint」有结论了，落盘 | 写 `.ai-memory/decisions/` 新记录（三段式：背景/决定/被否的选项），**不改旧记录**；若涉及约束变化同步改 `.ai/rules/` |
| 20 | 把这次会话定的「发布必须双人审批」沉淀下来 | 先判断类型：约束 → `.ai/rules/`；步骤 → `.ai-memory/playbooks/`；理由 → `.ai-memory/decisions/`；可能三者都需要，各写各的位置（一事一处）后 sync |
| 21 | 这次会话的进行中事项先记下来，下次接着干 | 写入 `.ai-memory/drafts/`（工作记忆草稿区，随时改写；个人内容用 `*.local.md`），不进 decisions |

---

## 不同工具的使用差异

| 工具 | 建议提示方式 |
|---|---|
| Claude Code | 直接说意图；复杂任务可「用 ai-config-editor 子代理」或「用 ai-config 技能」 |
| iFlow CLI / Gemini CLI | 直接说意图；可显式点名子代理 |
| Cursor / Trae / Qoder / ZCode / Codex / Copilot / CodeBuddy | 意图后补一句「按 `.ai-memory/playbooks/ai-config.md` 的流程执行」更稳（这些工具只有规则触发，无原生子代理/技能） |

## 防偷懒核对单（用户侧验收）

- [ ] AI 给出三件套了吗（源清单 / 派生输出 / 校验原文）？
- [ ] 源改动是否**全部**位于 `.ai/`？（出现其他路径 = 违规）
- [ ] `--check` 是否真的全绿（自己再跑一遍只要 1 秒）？
- [ ] 提交是否包含源 + 派生两部分？（只提交一半，钩子会拒绝）
