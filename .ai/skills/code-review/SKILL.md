---
name: code-review
description: 全面代码评审蜂群（code-review）执行卡。engine.code-review: manual——仅在用户对话显式要求时调用，无自动触发、无命令入口；leader 为 code-review-leader.md，唯一入库产物 contracts/<REVIEW-ID>.md。
---

# code-review 执行卡（全面代码评审蜂群）

**第 0 步（开关自检，强制）**：读取 `.ai/ai-coding-config.yml` 的 `engine.code-review`（文案口径 `engine.<蜂群名>`，本蜂群为 `code-review`）——
- `manual` → 仅当用户对话**显式要求**评审时进入本流程；未显式要求时按常规方式处理，不主动发起、不监听任何命令入口。
- `never` → 立即停止本流程并告知用户；按常规方式处理任务。
- 未配置（缺省）→ 视为停用，停止本流程并按常规方式处理。
- `auto` / `always` → 本蜂群不适用（评审不自动触发）；按常规方式处理并说明。

**无命令入口**：不创建 `/review`，不创建任何 ZCode / 各工具命令指针；评审入口只有用户显式对话要求。**不产生 REQ**：不创建、不修改任何 `REQ-*.md`；不写第二份 md；不建独立 baseline 文件；不写仓库内测试文件。与 `ai-coding-dev` 蜂群并存但互不触发：本蜂群只做评审、只交付一份 md，不进入四阶段契约流程、不改动工作树业务代码。

## 评审号与唯一产物

- 评审开始时生成评审号 `REVIEW-YYYYMMDD-HHMMSS`（**秒级时间戳，天然防撞**；不预占、不查重、不占用工作树、不写 baseline）。
- 唯一入库文件 `contracts/<REVIEW-ID>.md`。**只有 leader 有 Write/Edit 写该文件**；工人结论一律回传 leader，多轮打回只在**同一文件第 7 节追加**，绝不新建第二份 md。
- 报告结构：① 评审结论（APPROVE / REQUEST_CHANGES / BLOCK）② 评审输入 ③ 分级意见（P0–P3，每条含 文件:行 | 维度 | 问题 | 证据 | 建议 | origin=new|historical | 归属）④ 链路仿真与副作用 ⑤ 历史债隔离 ⑥ 证据清单（L1/L2 命令与输出摘录）⑦ 评审轮次记录。
- 模板：`.ai/skills/code-review/templates/review-report-template.md`；委派单：`.ai/skills/code-review/templates/task-brief-template.md`。

## 执行卡：阶段 0 → 1 → 2（四路并行）→ 3 → 4 → 5 → 写 md

| 阶段 | 关键动作 | 完成判据 |
|---|---|---|
| **0 事实清单** | leader 用 Read/Grep/Glob 读固定事实源，形成带 `file:line` 的清单；缺失标「基线缺口」 | 清单覆盖全部事实源，每条可定位；角色不得自行推导补充 |
| **1 取号与委派上下文** | 评审开始时生成 `REVIEW-YYYYMMDD-HHMMSS`；把事实清单 + 范围 + 边界写入委派单 | 评审号确定；每路委派单自包含 |
| **2 四路并行** | **同一轮**并行委派四路（见下），不逐个等待 | 7 个工人角色全部回报；信息隔离成立 |
| **3 裁决** | `verdict-arbitrator` 纯只读：去重分级 / 历史新增隔离 / 生成结论 | 结论回传 leader，**不落盘** |
| **4 多轮打回** | 有 FAIL / 争议则按轮次补证与复核；每轮结论回传 leader | 每轮记录可追溯；无新增 md 文件 |
| **5 定稿并写 md** | leader 按模板写入唯一 `contracts/<REVIEW-ID>.md`，再用 `validate-report.mjs` 机器校验结构 | 七节齐全；L1/L2 证据入第 6 节；SKIP 标明原因；校验 FAIL 项修复后才交付 |

### 阶段 0：事实清单（不写脚本）

leader 用 Read/Grep/Glob 静态读取以下**固定事实源**，逐条形成带 `file:line` 的事实清单，随委派上下文传给各角色：

1. 工程结构：各层 `pom.xml`、`pnpm-workspace.yaml`、`@FeignClient`、`Local*Client`。
2. 契约与后端：`*-api`、`@RestController`、`ServiceHeaders`/`KeyComposer`、`@Column(tenantId=true)`、Flyway 六目录（`business-leave`、`platform-auth`、`platform-infra`、`platform-scheduler`、`platform-system`、`platform-workflow` 各自 `src/main/resources/db/migration`）、`packages/core/src/api/*.ts`。
3. 调用链：`Controller→UseCase`、`Feign`、`api/*.ts` URL、SCSt binder、WS。
4. 运行时与租户：Redis 键与环境段、Nacos、`TenantBypassScope`/`DataScope`、XSS 名单、MSW handlers。
5. 规范：`.ai/RULES.md`、分层 `AGENTS.md`、`FRONTEND.md` / `BACKEND.md` / `UI-SPEC.md`。
6. 重复声明与冗余逻辑：自定义注解 `@interface` 声明、跨模块同名类/同名方法体、前端跨端重复类型与 HTTP 客户端（`packages/core` / `web` / `mobile` 的 `api/*.ts`）、同名 Flyway 迁移（须区分独立 location）、重复配置键/常量；每条带 `file:line`，**不得用 Spring 内建注解白名单替代全量扫描**。

清单缺失的项写「基线缺口」并附检索范围；任何角色**不得自行推导补充**清单外链路或事实。

### 阶段 2：四路并行（同一轮委派，7 个工人角色）

| 路 | 角色 | 职责要点 |
|---|---|---|
| A 契约事实 | `contract-preflight` | 契约质量、破坏性/静默变更、事实清单复核；形成带 `file:line` 的**事实清单** |
| B 双端实现 | `module-inspector-be`、`module-inspector-fe` | 后端正确性/边界/异常/并发/事务/安全/性能/时序；前端渲染/状态副作用/边界/XSS/资源/令牌规范；**互禁读对端实现** |
| C 链路与影响 | `flow-simulator`、`cross-contract-verifier`、`impact-oracle` | 沿事实清单推演时序/异常断层/补偿/幂等边界/消息顺序；三向一致/共享字段/隐式依赖/超时熔断；链式副作用/发布顺序/灰度回滚。**不构造清单外链路、不自行推导调用关系、不列举范围外下游** |
| D 质量 | `quality-reviewer` | 命名/复杂度/重复声明与冗余逻辑（无白名单扫描 + 三类判定）/分层边界/注释 TODO/i18n/设计令牌/可维护性 |

四路之外，`verdict-arbitrator` 在阶段 3 调用，纯只读、不落盘。委派单模板见 `.ai/skills/code-review/templates/task-brief-template.md`；信息隔离边界由 leader 在每份委派单中写死。

## 评审维度清单与角色映射

- **唯一源**：`.ai/skills/code-review/review-checklist.md`（Tier0 架构/设计/依赖/模块职责最高、Tier1 业务与正确性、Tier2 安全/性能/并发/数据库/异常日志/接口 RPC MQ/部署、Tier3 交 CI/L1）。清单四层之外的维度不得临时发明；用户 6 类概要已并入四层。
- **优先级**：Tier0 最高；**安全与架构重大缺陷一票否决**，优先级高于意见数量与其他层级；Tier3 由 L1/CI 或现有工具处理，蜂群只抽样复核并标「交 L1」，**不得把 typecheck 或测试通过表述为 lint 通过**，禁止为过检查新增依赖。
- **角色节选**：CP 契约预检=契约/版本兼容/DTO-VO 边界/返回字段脱敏/API 文档；BE 后端=**Tier0 全部** + Tier1/Tier2 后端部分；FE 前端=组件/状态/表单/防抖/路由权限/兼容/资源/内存泄漏/打包 + 前端 XSS/a11y 人工复核（禁读后端）；FS 链路=状态机/补偿/幂等/消息顺序/调用链异常；CV 跨契约=三向一致/DTO-BO-DO-VO 不混用/错误码/版本兼容/字段脱敏；IO 影响面=依赖引入/版本冲突/废弃漏洞/环境隔离/灰度回滚/容量/SLA/降级；QR 质量=技术债/复杂度/复用重复/方法长度/扩展点，风格类只抽样并标「交 L1」；VA 裁决=按 Tier 分级与一票否决，历史债隔离不变。详细映射见清单文件「角色映射」表。
- **委派**：阶段 1 的**每份委派单必须下发按角色节选的维度、优先级（Tier0 最高 / 一票否决）与交给 CI/L1 的项**；子 Agent 不读流程文档，委派单须自包含。

## 验证纪律（动态取证三层）

- **L0 静态**：8 个角色用 Read/Grep/Glob 分析事实清单与源码；不写任何文件。
- **L1 既有检查**：leader 经统一执行器 `run.mjs` 跑**已有**检查——`node .ai/sync.mjs --check`、`node .ai/skills/team-leader/scripts/check-contract.mjs <file>`、`pnpm typecheck`、命中模块既有 vitest、Maven 测试，以及 `.ai/skills/code-review/scripts/l1-checks.mjs` 的 `desktop-check` / `theme-tests` / `test-shard <包名>` / `build`（从 PATH 扫描 pnpm 入口，零新增依赖）；**不写新文件**（仅允许既有构建产物）。Tier3 交这些 L1/CI 项处理，蜂群只抽样复核；**不得把 typecheck 或测试通过表述为 lint 通过**，禁止造 lint、禁止新增依赖。
- **L2 探针**：高危怀疑点写 `.ai-tmp/code-review/<REVIEW-ID>/<role>/probe*.mjs`（**每工人独立子目录**，跨平台），`node` 运行；工人只清理本人子目录，leader 收尾统一经 `run.mjs --cleanup` 以 `fs.rm` 删除；`--cleanup` 作用域白名单禁止越权清理共享根目录。
- **分级**：Tier0（架构/设计/依赖/模块职责）与 Tier1 认证 / 租户 / 事务 / MQ 幂等顺序 / 双形态 / DTO 序列化 → L0 + L1 + 必要时 L2；普通 CRUD → L0 + L1；Tier3 纯文档样式 → L0（交 L1）。**安全与架构重大缺陷一票否决**，按 Tier 分级与 Veto 裁决。
- L1/L2 的**命令与输出摘录写入唯一 md 第 6 节**；失败或超时标 `SKIP(原因)`，不得静默略过。

**重复检测强制口径（防漏检）**：

- **无白名单扫描**：重复检测必须覆盖自定义注解 `@interface` 与自定义类型，不得仅用 Spring 内建注解白名单或已知类清单过滤候选；候选一律按 `file:line` 登记。
- **三类判定**：每个候选必须判定为 **真实重复 / 模块特化 / 探针假阳性** 之一并写出依据。「bean 名不同」「显式限定符不同」**只能**排除 Spring bean 冲突，**不得据此排除代码重复本身**；同名类必须进一步比对方法体、注解元信息（如 `@Target`）、异常口径差异后才可结案。
- **复核归属**：探针报出的候选（如 `RequirePermissionInterceptor`×4）不得由 leader 单方面按命名冲突排除，须回传 `quality-reviewer` / 对应 `module-inspector` 复核，按三类判定后再写入结论与第 7 节轮次记录。

## 唯一事实源约束

- 事实只认阶段 0 清单与 `.ai/` 源文件；清单缺口即「基线缺口」，禁止脑补、禁止把推测写成事实。
- 评审报告只写一份 `contracts/<REVIEW-ID>.md`；工人脚本只写 `.ai-tmp/code-review/<REVIEW-ID>/`；不存在第二份 md、独立 baseline 或 REQ 产物。
- 证据必须可定位（文件:行 + 命令 + 输出摘录）；无证据的意见降级为「疑点」并标注待证。

## 跨平台硬规范（所有写出的脚本必须逐条满足）

1. 一律 Node.js `.mjs`，用 `node` 运行；**禁止** `.sh` / `.cmd` / `.bat` / `.ps1` 文件。
2. 统一执行器 `.ai/skills/code-review/scripts/run.mjs`：只用 `child_process.execFile(file, args[], { shell: false })`；**禁止**拼 shell 字符串、`exec` / `spawn` 带 `shell` 真值、`&&` / `|` / 重定向语法。
3. 禁止平台专属命令（`grep` / `sed` / `findstr` / `Select-String` 等），禁止硬编码 `C:\...` 或 `/usr/...`；路径一律 `path.resolve` / `path.join`，参数统一正斜杠。
4. Maven 解析**零平台分支**：优先 `process.env.ACME_MVN`，其次 PATH 中 `mvn`，都没有明确报错「请设置 ACME_MVN」；不写 `mvn` 的 Windows 专用后缀名、不按 `process.platform` 分支。
5. 清理用 `fs.rm(p, { recursive: true, force: true })`，不用 `rm -rf`。
6. 环境变量只经 `process.env`；LF 换行、UTF-8 无 BOM；Node 路径用 `process.execPath`。

**工人脚本边界**：7 个工人角色（`contract-preflight` / 两个 module-inspector / `quality-reviewer` / `flow-simulator` / `cross-contract-verifier` / `impact-oracle`）的 Write/Bash 只能用于把审核脚本写到 `.ai-tmp/code-review/<REVIEW-ID>/`，且必须遵守上述六条、用完经 `run.mjs --cleanup` 删除；**不得写仓库内任何文件、不得写第二份 md、不得写业务代码**。leader 的 Bash 也仅限 `node <script>.mjs`。

## 路径速查

- 执行卡：`.ai/skills/code-review/SKILL.md`（本文件）；评审清单：`.ai/skills/code-review/review-checklist.md`；角色源：`.ai/agents/code-review/`
- 脚本：`.ai/skills/code-review/scripts/`（`run.mjs`、`l1-checks.mjs`、`validate-report.mjs`）；模板：`.ai/skills/code-review/templates/`
- 唯一产物：`contracts/<REVIEW-ID>.md`；临时脚本：`.ai-tmp/code-review/<REVIEW-ID>/`（用完删除）
- 取舍与边界：`.ai-memory/decisions/2026-11-26-02-code-review-swarm.md`
