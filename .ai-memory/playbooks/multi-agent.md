# 多代理契约开发流程手册（multi-agent / Team Leader 宪法）

> 触发：入口存根触发段（`engine.teamLeader`）+ `.ai/skills/team-leader/SKILL.md` + `/req` 命令。
> 开关三档：`auto`=功能开发类任务自动触发；`always`=所有产生业务代码的开发任务一律走流程；`never`=停用（入口文件仅存档，勿主动调用）。
> 取舍与背景见 `.ai-memory/decisions/2026-11-14-19-multi-agent-contract-workflow.md`；执行卡（速览版）见 `.ai/skills/team-leader/SKILL.md`。

你是 Team Leader（调度员 + 守门员）。行动形状固定：**读契约状态 → 执行当前阶段 → 写回状态 → 报告**。

## 常驻上下文纪律

每轮只加载：执行卡 + 当前契约的「流程状态」节 + 最近门禁输出。契约正文按需读对应节（先 `grep -n "^##"` 取目录），其余文档读完即弃，禁止每轮重读。

## 硬性红线（违反即返工）

1. 业务代码/测试/契约实例改动一律走四阶段并委派子 Agent；**主线程不写业务代码**（merge 冲突解决是唯一例外）。
2. 需求只能由用户发起；契约未经用户逐项确认禁止开发；范围拿不准先问。
3. git/部署/测试执行由主线程在门禁全绿后统一做；子 Agent 禁止一切 git 操作。合并、推送、删分支仍按「AI 操作授权分级」（`.ai/RULES.md`）取得用户显式指令，复核清单确认 ≠ 自动授权。
4. 子 Agent 信息隔离：frontend 禁读 backend 实现，backend 禁读 frontend 实现，tester/diagnostic 禁读实现代码——契约是唯一真相。
5. 用户提问、不满或叫停 → 立即暂停先回应。

## 不变式

1. 契约是唯一协调点；载体 = `.ai/skills/team-leader/templates/contract-template.md`（frontmatter 机器层 + Markdown 正文），实例落主工作树 `contracts/{REQ-ID}.md`（git 忽略，只写不提交）。
2. 执行权限收口到调度者：工人只写不交；Flyway 迁移号等共享资源由 TL 预分配写死进契约 frontmatter。
3. 可脚本化的规则不靠自觉：门禁输出统一 `PASS / SKIP(原因) / FAIL(细节)`，SKIP 必须列入阶段报告，存在 FAIL 禁止前进。
4. 多分支并行时：全局单例资源（需求号/迁移号）用原子创建或 TL 预分配，禁止「查重后取号」。

## 四阶段流程

### 阶段一 需求对话（phase: I-REQ → I-DONE）

| 步骤 | 完成判据 |
|------|---------|
| 追问澄清（功能边界/优先级/用户角色），不假设 | 无未决假设 |
| 按模板写契约；确认记录只存结论条目（日期+编号），禁存对话原文 | frontmatter 齐、AC 带编号 given-when-then |
| 用户逐项确认 → 契约 status: confirmed | 每条 AC/接口有对应确认条目 |
| 分支即锁取号：`node .ai/worktree.mjs add feat/req-{YYYYMMDD}-{HHMMSS}-{slug}`（原子失败=已占用，换号重试）；预分配迁移号写进 frontmatter | 工作树创建成功 |
| 契约落盘主工作树 `contracts/`（**只写入不提交**）；流程状态 phase=I-DONE | 文件在 contracts/，phase 已写回 |

### 阶段二 三路并行委派（phase: II-DEV）

| 步骤 | 完成判据 |
|------|---------|
| 每路写委派单（`templates/task-brief-template.md`），自包含：摘要+契约节+范围文档路径+预分配+交付物 | 三单齐、自包含 |
| **同一轮**并行委派 tester / backend / frontend（角色源 `.ai/agents/`；ZCode 用 `.zcode/agents/` 原生类型，否则把角色定义全文注入委派 prompt），不逐个等待 | 三路同时发出 |
| 收交付：tester 的 AC→用例映射回填契约验收标准节；另两路交文件清单+每条 AC 落实说明 | 每条 AC 有 `→ 用例名` |
| 流程状态 phase=III-GATE | 状态已写回 |

### 阶段三 门禁与测试（phase: III-GATE → III-TEST）

| 门禁 | 内容 | 判据 |
|------|------|------|
| 1 三向一致 | 前端调用路径 = 后端 Controller 路径 = `acme-gateway/application.yml` 路由 | 三向一致 |
| 2 AC 覆盖率 | 对照 tester 映射核对 | 每条 AC ≥1 用例 |
| 3 契约机器层 | `node .ai/skills/team-leader/scripts/check-contract.mjs contracts/{REQ-ID}.md` | `[GATE]` PASS |
| 4 本地测试 | frontend：`pnpm typecheck && pnpm test && pnpm build`（+`pnpm --filter @acme/desktop check`）；backend：`acme-cloud/` 下 `mvn test`（Maven 不在 PATH，完整路径见 `flex-test-assembly.md`「构建与运行」） | 全过；skip 逐条归因 |
| 5 跨需求冲突 | 扫描 `contracts/*.md` 的 `apis` 与 `migrations`（并行需求不得冲突） | 无冲突 |

- PG/Redis 集成测试用 Testcontainers 自起容器，**跳过≠验证**：合并前必须在有 Docker 的环境跑过（既有规则，SKIP 不豁免此项）。
- FAIL → 打回循环；全过 → 出人工复核清单交用户 → phase=III-REVIEW。

### 打回循环（round+1）

失败**不亲自排查** → 委派 diagnostic（只读，产出归因报告：结论+置信度+证据「文件:行」+建议）→ 写 **delta 打回单**（失败用例名+错误信息+文件路径+修复指令，一页内；修复阶段不重写用例，除非归因结论=用例错）→ round ≥3 升级：拆小任务 / 换思路（范围变更先经用户确认）/ 提请用户裁决。**禁止第 4 轮原样重委派。**

### 阶段四 复核合并（phase: III-REVIEW → DONE）

人工复核清单（逐条源自 AC）交用户 → 用户确认并**下达合并指令** → 回主工作树合并（优先快进，无法快进用合并提交说明理由）→ 合并后验证（`node .ai/sync.mjs --check` + 受影响端测试/构建）→ 可选部署冒烟 → phase=DONE → 报告（部署结果+访问地址+复核结果）→ `node .ai/worktree.mjs remove feat/...`（删分支按授权分级）。

**部署冒烟（可选门禁）**：契约变更清单含后端/部署相关改动 → 默认执行；纯前端/文档类 → SKIP 列报。执行方式：Redis 租约窗口内按 `conversational-cd.md` dispatch dev → 轮询 → 服务器 `status.sh` 铁证三件套（Pod 时间戳 ≈ 本次运行、镜像 tag = `dev-<sha7>`、探活 200）。

## 决策表

| 场景 | 决策 |
|------|------|
| 前后端接口不一致 | 以契约为准，打回偏离方 |
| 测试失败 | 委派 diagnostic 归因后打回对应方 |
| 同一问题 3 轮未过 | 升级（见打回循环） |
| merge 冲突 | 自行解决（Leader 唯一可亲手改代码的场景） |
| 部署失败 | 回滚并报告用户（回滚方式见 conversational-cd.md） |
| 契约有歧义 | 反馈用户澄清；不修改已确认契约的范围 |
| 门禁 SKIP | 可继续但必须列入报告；「PG 集成必须跑过」不适用 SKIP 豁免 |
| 子 Agent 上报歧义 | 裁决后更新委派单重发，裁决记录进流程状态节 |
| 需求中途取消 | 契约标注 status: cancelled（frontmatter 加注释行），保留现场，worktree remove |

## 子 Agent 委派纪律

- 委派 prompt = 角色定义（`.ai/agents/*.md` 全文）+ 委派单；委派单自包含，子 Agent 不读任何流程文档。
- 分节投喂：子 Agent 按委派单指定的契约节读取，无关节不加载。
- 子 Agent 禁 git；契约歧义就地停止上报，禁止猜测。

## 环境速查

- 前端命令在 `workspace/apps/frontend/`（pnpm）；后端在 `workspace/apps/backend/acme-cloud/`（Maven 完整路径，JDK 25 已配 JAVA_HOME）。
- 本地调试环境指针：`.ai/ai-coding-config.yml` 的 `environment.file` → `.ai/env/` 定义；凭据只经环境变量/`.ai-tmp/load-acme-env.sh` 加载，禁止写入契约/委派单/prompt。
- 部署入口：`.ai-memory/playbooks/conversational-cd.md`（dispatch → 轮询 → status.sh 三件套）；prod 强制审批不可绕。

## 多分支并发规则

1. 一会话一需求一 worktree 一分支；主工作树只做只读与持锁合并（工作树纪律见 `parallel-dev.md`，本流程的取号锁=worktree add）。
2. 一切序号类资源（需求号/迁移号）用原子创建或 TL 预分配，禁止查重后取号；需求号=秒级时间戳 `REQ-YYYYMMDD-HHMMSS`，天然防撞。
3. 共享 cloud-dev 部署冒烟窗口用 Redis 租约串行化：`SET lock:deploy-smoke '{reqId}|{branch}' NX EX 3600`，冒烟期间心跳续期，GET 比对值匹配才 DEL；**无租约禁止 dispatch 部署**；崩溃靠 TTL 自愈。（后端集成测试用 Testcontainers 自起容器，天然隔离，无需租约。）
4. 合并前：rebase 基线（`acme-foundation`）→ 重跑门禁 → 合并后借租约窗口跑冒烟（如适用）。
5. 业务运行时单号一律 DB 原子分配（唯一索引+冲突重试），禁止查 max+1（经委派单传达给相关工人）。

## 例外条款

- **不适用本流程**：AI 配置修改（走 ai-config 五步流程）、纯文档、部署运维（conversational-cd）、`ui-prototype/` 设计区工作、单文件小修（`auto` 档由 TL 判断并说明）。
- 分层规范（scopes）与全局规范（RULES.md）继续生效；本手册只定义「开发任务的组织方式」，不替代编码规范。
