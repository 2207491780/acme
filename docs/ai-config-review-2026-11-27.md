# AI Coding 配置体检报告（2026-11-27）

> 检查对象：`.ai/` 全部源配置、派生入口、钩子、CI、`.ai-memory` 关联手册/ADR、contracts 治理面。
> 检查方式：通读源文件 + `node .ai/sync.mjs --check` + `node --test ".ai/tests/*.test.mjs"` + 关键路径存在性脚本核验 + 真实 git 钩子链路实证。
> 性质：只读分析，未修改任何配置；修复留待后续会话（按「先分析收敛、另起会话开发」工作模式）。

## 总体结论

**派生机制完整可用、当前无派生漂移；规则体系逻辑通顺、口径高度一致。** 发现 1 处文档与实际相反的过时口径（P2）、2 处权威文档陈旧宣称（P3）、1 处 Windows 本机测试 harness 缺陷（P3）、5 处表述小瑕疵（P4）、3 项治理观察（含主题分析稿失踪）。

| 维度 | 判定 |
|---|---|
| 逻辑通顺 | ✅ 通过（四阶段/五步/三防线/授权分级/落盘分流互相咬合，无循环或断链） |
| 是否存在漂移 | ⚠️ 派生物零漂移（--check 197 全绿、无孤儿）；**文档口径漂移 3 处**（#1–#3） |
| 表述一致无歧义 | ✅ 基本一致；5 处小瑕疵（#5a–e），均有测试锁定的核心口径未回退 |
| 派生机制完整可用 | ✅ 完整可用（证据见「机制完整性核查」）；本机 Windows 回归有 2 用例假红（#4） |

## 一、验证证据摘要

```text
node .ai/sync.mjs --check        → 校验通过：197 个派生文件/链接均与 .ai/ 同步，无孤儿残留
node --test ".ai/tests/*.test.mjs" → 159 用例：157 过 / 2 挂（#4，CI ubuntu 全绿）
.ai/RULES.md 实测 10178 bytes ≤ rulesBudgetBytes 10752（余量 5.4%）
git ls-files contracts → 57 个文件已入库；git status 仅 2 个未跟踪 REVIEW 产物
git config core.hooksPath → .ai/hooks（钩子已启用）
.ai/env/：local/cloud-dev/cloud-prod 三文件在位；environment.file=cloud-dev.yaml 存在 ✓
.ai-memory：56 个 ADR、9 个 playbook 全部在位；文档引用的 ADR/playbook 路径 100% 存在（脚本核验）
```

## 二、问题清单（按严重度）

### #1 [P2] contracts/README.md 宣称已废除的「不提交」政策，与实际相反

- **位置**：[contracts/README.md](../contracts/README.md)——「**只写入不提交**：本目录内容 git 忽略（仅本 README 入库锚定目录）」。
- **实际**：`git ls-files contracts` = 57（全部 REQ 契约 + README 均已入库）；[.gitignore](../.gitignore) 注释「契约文件（**2026-11-20 起入库**：跨会话/跨机可见……）」；[multi-agent.md](../.ai-memory/playbooks/multi-agent.md) 不变式 1「实例落主工作树 contracts/{REQ-ID}.md（**已入库**，2026-11-20 起：draft 即提交、confirmed 后状态更新再提交）」；ai-coding-dev-leader / team-leader SKILL 同口径。
- **影响**：README 是该目录的锚定说明，任何 AI 或人据此会得出「契约不提交」的错误操作指引，直接违背取号防撞（迁移号/需求号随 git 协调）与跨会话/跨机可见的设计目标。政策翻转（2026-11-20）只改了 .gitignore/手册/角色卡，漏改了 README。
- **修复方向**：按 multi-agent.md 不变式 1 改写 README 的提交口径（保留「并行工作树内经 ../../contracts/ 跨树读取」等仍正确的部分）；顺带评估为 2026-11-20 政策翻转补一条 ADR（目前只有三处行内提及，无独立决策记录）。

### #2 [P3] ARCHITECTURE.md 仍宣称已退役的 cloud-test.yaml（两处）

- **位置**：[.ai/ARCHITECTURE.md](../.ai/ARCHITECTURE.md) 目录树注释「env/ … **local/cloud-dev/cloud-test/cloud-prod**」；「MCP 服务器配置」表「`.ai/env/{local,cloud-dev,cloud-test,cloud-prod}.yaml`」。
- **实际**：[ENV-GUIDE.md](../.ai/env/ENV-GUIDE.md)「2026-11-18/19 部署形态改造……test 并入 dev、**cloud-test.yaml 退役**」（ADR 2026-11-18-02）；磁盘实为三文件。
- **影响**：ARCHITECTURE 自称「体系唯一权威说明」，按图索骥会扑空；MCP 表「其余为空模板」的陈述随之含糊。
- **修复方向**：两处删去 cloud-test，与 ENV-GUIDE 对齐。

### #3 [P3] ARCHITECTURE.md 仍把已退役的 cd.yml / ai-rules-sync 列为现行

- **位置**：同文件目录树注释「ci/ → .github/workflows/（**ci / cd / ai-rules-sync**，见 CI-GUIDE.md）」；「CI/CD」表列 `cd.yml`（dispatch 选环境 / `v*` 标签 → prod、GitHub Environment 审批）为现行工作流。
- **实际**：[CI-GUIDE.md](../.ai/ci/CI-GUIDE.md)「原 `cd.yml` 已退役（ADR 2026-11-18-03 CI/CD 本地化……本机 local-build.sh / local-deploy.sh 承接）；原 `ai-rules-sync.yml` 亦已裁撤（ADR 2026-11-14-23）」；`.github/workflows/` 实际仅 ci.yml。
- **影响**：同 #2；对 CD 链路的描述会误导读者去 GitHub 找 dispatch/Environment 审批（实际是本机脚本 + 人工执行）。
- **修复方向**：目录树注释与 CI/CD 表改为「仅 ci.yml 门禁；发布本地化（local-build/local-deploy，ADR 2026-11-18-03）」。

### #4 [P3] 引擎回归测试在 Windows 本机 2 个用例假红（测试 harness 可移植性缺陷）

- **位置**：[.ai/tests/hooks.test.mjs](../.ai/tests/hooks.test.mjs) 的 `shellForHooks()`；失败用例 `should_reject_policy_push_when_ai_config_mirror_differs`、`should_pass_policy_push_when_ai_config_mirror_matches`。
- **根因**（已实证）：测试直接 spawn Git 的 `usr\bin\sh.exe` 跑 [pre-push](../.ai/hooks/pre-push)，该方式下 msys 转换后的 PATH 不含 `/usr/bin` → `dirname`（pre-push 第 87 行 `hookdir=$(cd "$(dirname "$0")" && pwd)`）不可用 → `hookdir` 错位为沙箱 repo → `mirror-check.mjs` MODULE_NOT_FOUND。
- **关键实证**：真实 git 钩子链路（git 从 PowerShell 执行 hook → sh）中 `dirname=ok`、`hookdir` 解析正确——**真实推送在本机 Windows 不受影响**；CI（ubuntu）不受影响。
- **影响**：本机 Windows 无法得到全绿回归基线（157/159），且此差异未在任何文档记载（ci.yml 注释只记录了 `node --test` 目录形式问题）。
- **修复方向**（任一）：① 测试改用真实 git 链路触发钩子（临时 bare remote + `git push`，或 `git hook run`）；② `shellForHooks()` 解析出的 sh 在 spawn 时把 `<git-root>/usr/bin` 注入 PATH。修好后应把「本机 Windows 跑回归」的口径写进 ARCHITECTURE/CI-GUIDE。

### #5 [P4] 表述小瑕疵（不造成误操作，但口径可再收紧）

| # | 位置 | 问题 |
|---|---|---|
| a | [.ai/agents/AGENT-POOL.md](../.ai/agents/AGENT-POOL.md)「九个角色」 | 列出 8 个名字 + leader，与 ai-coding-dev 条目「五个工人角色 + leader」的计数风格不一致；且未点出 verdict-arbitrator 的裁决员特殊地位（leader/SKILL/回归测试的权威口径是 7 工人 + leader + 裁决员 = 9） |
| b | [.ai/ARCHITECTURE.md](../.ai/ARCHITECTURE.md)「TUTORIAL.md（11 类工具…）」 | TUTORIAL 实为 10 个编号小节（第 10 节为 AGENTS.md 标准族） |
| c | [.ai/PROMPTS.md](../.ai/PROMPTS.md)「所有工具入口（8 个）都注入了铁律」 | 表述过强：8 个入口中 4 个内联铁律节选、CODEBUDDY 经 `@` 导入全文、trae/qoder/cursor 为纯指针（靠工具自动读根 AGENTS.md 或 Agent 主动读 RULES.md 兜底） |
| d | [multi-agent.md](../.ai-memory/playbooks/multi-agent.md)「开关三档」+ ai-coding-dev leader 第 0 步 | 配置 schema 允许任意蜂群键取 `manual`，但 ai-coding-dev 的 leader/SKILL 第 0 步未定义 manual 分支（code-review leader 四档全覆盖，存在不对称）；若 `engine.ai-coding-dev: manual` 行为未定义。另 manual「无命令入口」语义与 `/req` 命令并存的组合未说明 |
| e | [.ai/rules/project.md](../.ai/rules/project.md)「视觉事实源 `packages/core/src/theme.ts`」 | 该路径相对 frontend monorepo 根（实际在 `workspace/apps/frontend/packages/core/`）；根级 RULES.md 读者需自行拼前缀。分层规则内同写法成立，属轻微歧义而非错误 |

## 三、治理观察（不判问题，供决策）

1. **主题分析稿失踪**：[contracts/REQ-20261126-125836.md](../contracts/REQ-20261126-125836.md) 第 85 行曾记录主工作树存在 `docs/theme-optimization-plan-2026-11-26.md` 并发改动——该文件现已不在磁盘、git 全历史无记录（`git log --all` 为空）。用户工作记忆称该稿 2026-11-26 已交付（含 DD1–DD10 十个待裁决决策点）。现状 = 记忆锚点悬空、契约引用悬空。建议从当次会话的交付产物找回或重建并入库，否则 DD1–DD10 裁决依据丢失。
2. **REVIEW 产物未提交且双树同名**：`contracts/REVIEW-20261127-010308.md` / `REVIEW-20261127-044012.md` 未跟踪，且在主树与 `.worktrees/feat-req-20261126-125836-remove-multi-client-releases/` 两处同名并存（疑似重复产出或拷贝）。按 code-review 蜂群口径「唯一入库产物 contracts/<REVIEW-ID>.md」，应甄别后提交归位。
3. **.ai-tmp/ 积累**：数百个历史文件（含多个测试沙箱残留 hooks-test-\*/sync-test-\*/mirror-test-\* 及 cloud-creds.env / registry.env 凭证文件）。均在 gitignore 内、无入库风险，但建议定期清仓；凭证文件虽不入库，长期滞留本机仍有暴露面。

## 四、机制完整性核查（正面确认）

- **派生引擎**（sync.mjs 通读）：登记簿预检后写、路径防穿越、拒绝符号链接源/中段链接、派生目标冲突登记（含 Windows 大小写重合检测）、RULES 预算先检后写（超限中止且零写入）、孤儿检测（带派生标记才报，不影响用户自建内容）、localOnly 目标「缺失不判错、存在必须一致」、CI 内联 AI_PATHS 与 ai-paths.list 强制逐条一致、蜂群布局不变量（根级仅蜂群目录 + 每蜂群必有 `<名>-leader.md`）先于配置加载校验、配置 schema 严格校验（未知键/非法枚举/类型/数组/Tab 一律报错）+ environment.file 存在性校验。
- **入口链路**：根 AGENTS/CLAUDE(@导入)/GEMINI/CODEBUDDY(@导入+去重) + copilot/trae/qoder/cursor 存根 + 3 个分层 AGENTS.md + 同级 CLAUDE.md(@导入) + Gemini/Trae frontmatter 适配 + 蜂群扁平派生 + ZCode/CodeBuddy localOnly 存根——全部由 `--check` 逐字节锁定，蜂群触发段（5/6 两条）与 `engine.ai-coding-dev: auto` / `code-review: manual` 一致。
- **三防线闭环**：规则层（入口注入）→ 本地钩子（pre-commit 暂存完整性 + commit-msg 规范 + pre-push 四重拦截与镜像预检；`core.hooksPath=.ai/hooks` 已启用）→ CI（ai-config 全分支 + mirror-sync 双分支镜像；重作业限主线/PR）。
- **口径交叉一致**（抽验全部命中）：五步流程（playbook/GUIDE/skill/PROMPTS 四处一致）、契约入库（三处一致，唯 README 例外即 #1）、engine 键引用（leader/SKILL/req 命令/入口触发段/配置五处一致）、code-review 的 manual/无命令入口/唯一产物/active set（leader/SKILL/清单/模板/校验器/配置/入口触发段一致，且由 swarm-docs.test.mjs 等回归锁定）。
- **引用完备性**：文档引用的 56 个 ADR、9 个 playbook、各总览文档（FRONTEND/BACKEND/UI-PROTOTYPE/UI-SPEC/ENV-STACK/RUNBOOK）全部存在；env 与声明一致（cloud-dev 登记 dev-db MCP，local/cloud-prod 空模板）；.gitignore 对 localOnly（.zcode/.codebuddy）、.ai-tmp、凭证的覆盖与设计一致。

## 五、覆盖边界

以下部分未逐字审读，其正确性由替代保障覆盖（`--check` 逐字节同步 + 回归测试锁定 + 本轮全绿）：8 个 code-review 工人角色正文、code-review 的 scripts（run.mjs / l1-checks.mjs / validate-report.mjs）与 team-leader 的 check-contract.mjs 源码、`.ai/tools/` 下 8 份工具说明正文、蜂群模板（contract/task-brief/review-report）正文、ai-coding-dev 其余工人角色（backend-dev/frontend-dev/tester/diagnostic/ai-config-editor）正文。CD 链路（local-build/local-deploy）未实际执行（已退役 CI 形态，属部署域而非 AI 配置域）。
