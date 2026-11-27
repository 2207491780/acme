---
requirementId: "REQ-20261127-234335"
title: "B1 P0 安全：RLS 租户隔离收口（V-01）——铁证角色 NOLOGIN + 旁路判据绑定可信角色（V37）"
status: confirmed         # 用户 2026-11-28 对话确认（推荐口径①②③逐项通过）；confirmed 后禁止范围外改动
branchContext:
  baseBranch: acme-foundation
  mergeTarget: acme-foundation
  featureBranch: "feat/req-20261127-234335-rls-tenant-isolation"
migrations:
  platform-system-service: "V37"   # 预分配；worktree add 前 git fetch 并重测（当前最新=V36），撞号则顺延并更新本行
apis: []                           # 无 HTTP API 变更（纯 DB 迁移 + 测试 + 文档批）
---

# B1 P0 安全：RLS 租户隔离收口（V-01）

## 背景

- V-01 事实（已复核 `V33__rls_pilot.sql:20,38,45-51`）：明文口令 `acme_rls_app/acme_rls_app_pwd` + GRANT 13 张含 tenant_id 表；策略旁路分支仅凭会话可自设 GUC `app.tenant_bypass='on'`——任何能建立 PG 会话的主体自设该 GUC 即跨租户读写（评审 Tier0 安全 Veto，origin=historical）。
- 旁路链现状（已读定案）：`@IgnoreTenant` 切面 → `TenantBypassScope.open`（framework-tenant）→ `TenantBypassSignal`（common-context 线程态深度计数）→ `RlsPropagationSupport`（DataSource 三层代理，事务首条 SQL 前 `set_config('app.tenant_bypass','on',true)`）。主代码 GUC 写入点唯一 = RlsPropagationSupport → **旁路判据改造无需改 Java**，联动 = 迁移内授权矩阵。
- 连接身份事实：prod 应用连接角色 = `${PG_USER:acme}`（postgres 容器 `POSTGRES_USER=acme` 为初始超管）；IT 基座 `AbstractSystemIntegrationTest` 应用连接 = 嵌入式 PG 超管 `postgres/postgres`；唯一直连非超管角色的测试 = `REQ202611251737RlsNonSuperuserIntegrationTest`（应用 DataSource=acme_rls_app、Flyway=超管、独立库 acme_rls_nonsuper）。**PG 角色是集群级对象**：V37 的 NOLOGIN 对 prod/dev/IT 全部生效（IT 用例经夹具重开）。
- 暴露面核验（方案待证①，只读结论）：env-stack k8s 清单中 postgres Service=ClusterIP（无 `type`/nodePort/hostPort/hostNetwork），全栈无 NetworkPolicy 资源，Ingress 仅 web；外部可达路径唯一 = `dev-tunnel.sh`（ssh -L 经应用面机，prod 禁用隧道）。主机防火墙/云安全组不在仓库清单可核范围 → **部署侧待核**；本修复无论暴露面结论均成立。
- 决策⑥（owner 2026-11-27 拍板）：prod NOLOGIN + test 联调值经配置注入；旁路判据另绑可信主体。
- 已知边界（不在本批）：prod 应用连接角色当前为容器初始超管（superuser 天然绕过 RLS），属既有部署形态，本批不改（登记待确认项③）；密钥扫描 = CI/L1 移交项（方案第 7 节，确认⑥）。

## 确认记录（契约的权威解释；只存结论条目）

- [2026-11-27] ① 修复载体 = system 域新迁移 V37（预分配）；V33 禁改（Flyway 校验和已固化），V37 头注释说明与 V33/V-01 的修复关系。
- [2026-11-27] ② acme_rls_app 收口 = 迁移内无条件 `ALTER ROLE acme_rls_app NOLOGIN` + `PASSWORD NULL`（静态 SQL、无环境分支/占位符）；PG IT 铁证用例的 LOGIN 由测试夹具在 Flyway 迁移完成后经配置注入口令重开——决策⑥「test/dev 保留 LOGIN」按此夹具重开语义落地（解释口径见待确认项①）。
- [2026-11-27] ③ 旁路判据定案 =「自设 GUC + 可信角色成员」联合判定（不采用 SECURITY DEFINER 函数——无更强信任源且多一层封装）：新增 NOLOGIN 角色 `acme_rls_bypass`，策略 USING 与 WITH CHECK 同式：
  `tenant_id = current_setting('app.tenant_id', true) OR ( current_setting('app.tenant_bypass', true) = 'on' AND pg_has_role(current_user, 'acme_rls_bypass', 'member') )`
  授权矩阵：`acme_rls_bypass` GRANT 给 `acme_rls_app`（IT 铁证路径）与 `acme`（prod 应用连接角色，存在性守卫，IT 环境无此角色时跳过）。
- [2026-11-27] ④ 框架侧零 Java 改动（TenantBypassScope / TenantBypassSignal / RlsPropagationSupport / CrossTenantAuditLogger / TenantKeyGenerator 原样）；`BACKEND.md` 部署红线段补 RLS 角色与口令注入口径。
- [2026-11-27] ⑤ 测试同步：`RlsNonSuperuserIntegrationTest` 口令改配置注入 + 新增「自设 GUC 旁路失效」铁证用例；`RlsPolicyIntegrationTest` 策略口径断言升级新联合判定式 + 幂等重放改为 V33→V37 顺序重放（防旧口径回潮假绿）。
- [2026-11-27] ⑥ 密钥扫描不进本批，移交 CI/L1（本契约不含密钥扫描任务）。
- [2026-11-28] ⑦ 用户对话指示继续，待确认项①②③按 leader 推荐口径逐项确认：①迁移全环境无条件 NOLOGIN + IT 夹具配置注入重开；②新角色命名 acme_rls_bypass；③两项登记（prod 超管角色收敛 / tenant_id 绑定可信主体评估）留后续专项，不进本批。

## API

无 HTTP API 变更（frontmatter `apis: []`）；无 Result/错误码变化。变更全部在 DB 迁移与测试层。

## 验收标准（AC 为规格核心；用例名=计划名，tester 交付后回填确认）

- AC-1 given: V37 已应用（超管会话重放 V37 全部语句后） when: 以 `acme_rls_app` + 旧口令尝试 JDBC 直连，并查 `pg_roles.rolcanlogin` then: 直连被拒（`is not permitted to log in`），rolcanlogin=false → `should_rejectLegacyPasswordLogin_when_v37NologinApplied`（policy 测试新增）
- AC-2 given: 非旁路成员的普通登录角色（测试临时创建：非超管、非 acme_rls_bypass 成员、仅授试点表 SELECT） when: 该角色会话仅自设 `app.tenant_bypass='on'`（不设 app.tenant_id）后 SELECT/INSERT/UPDATE/DELETE 试点表 then: 全部 0 行命中（旁路分支单独失效，fail-closed）→ `should_hitZeroRows_when_plainRoleSelfSetsBypassGuc`（non-superuser 测试新增铁证）
- AC-3 given: `acme_rls_app`（acme_rls_bypass 成员） when: 经框架合法路径 `TenantBypassScope.open` + 事务传播 then: 旁路生效（成员 + GUC 可信组合，可见全租户）——既有旁路铁证保持全绿 → `should_seeAndModifyAllTenants_when_bypassActiveOnNonSuperuserConnection`（既有改造保持）
- AC-4 given: 未设任何会话变量 when: `acme_rls_app` 查询/写试点表 then: fail-closed 空集（既有语义不回归）→ `should_returnEmptySet_when_noContextAndNoBypassOnNonSuperuserConnection`（既有保持）
- AC-5 given: 绑定租户上下文 when: `acme_rls_app` SELECT/UPDATE/DELETE then: 仅命中本租户行（租户隔离铁证不回归）→ `should_isolateDirectSqlToBoundTenant_when_nonSuperuserApplicationRoleUsed`（既有保持）
- AC-6 given: V37 已应用 when: 依次重放 V33、V37 全部语句 then: 无报错、策略数量与试点表集合不变，且策略表达式 = 新联合判定式 → `should_replayV33ThenV37WithoutErrorOrDuplicatePolicies_when_migrationsReplayed`（既有重放用例改造）
- AC-7 given: V37 已应用 when: 检查角色与授权矩阵 then: `acme_rls_bypass` 存在且 NOLOGIN/非超管；`acme_rls_app` 为其成员；13 张试点表全部 ENABLE+FORCE RLS 且策略口径 = 新联合判定式 → `should_bindBypassToTrustedRole_when_v37Applied`（policy 测试新增）
- AC-8 given: 测试代码与配置 when: NonSuperuser 铁证用例以配置注入口令建立 `acme_rls_app` 连接 then: 连接成功且 current_user=acme_rls_app、非超管；Java 源无口令明文常量（旧常量移除，来源=属性键，见钉死事实）→ `should_connectAsNonSuperuserApplicationRole_when_usingConfiguredCredentials`（既有用例改造）

## 变更清单（差异契约）

- 数据（`platform-system-service/src/main/resources/db/migration/system/V37__rls_tenant_isolation_hardening.sql`，幂等口径照 V30/V31/V33 先例）：
  - `ALTER ROLE acme_rls_app NOLOGIN` + `PASSWORD NULL`
  - 幂等守卫创建 `acme_rls_bypass`（NOLOGIN）+ GRANT 授权矩阵（acme_rls_app 必授；acme 存在性守卫授）
  - 13 张试点表 `DROP POLICY IF EXISTS rls_tenant_isolation` → 按确认③联合判定式重建（USING/WITH CHECK 同式）
  - 迁移头注释：修复关系（V-01、V33 保持原样的原因、决策⑥）
- 测试（`platform-system-service/src/test/.../system/`）：
  - `REQ202611251737RlsNonSuperuserIntegrationTest`：移除 `APP_PASSWORD` 明文常量→配置注入；新增 Flyway 迁移后重开 LOGIN 的夹具（推荐定制 `FlywayMigrationStrategy`）；新增 AC-2 铁证用例；既有用例适配
  - `REQ202611251737RlsPolicyIntegrationTest`：`assertPolicyExpression` 升级新口径（含 pg_has_role）；新增 AC-1/AC-7 断言；重放用例改 V33→V37 顺序（AC-6）
- 文档：`workspace/apps/backend/BACKEND.md` 部署红线段补 2~4 行：RLS 铁证角色 V37 起 NOLOGIN；IT 联调口令经配置注入（属性键 + 环境变量名），禁止回填明文
- 前端 / 框架（framework-tenant、common-context）：无改动

## 钉死事实（实现环境硬约束；委派单引用本节）

- 门禁命令：Maven 不在 PATH，`"C:\Program Files\Maven\apache-maven-my\bin\mvn.cmd"`；在 `workspace/apps/backend/acme-cloud/` 下执行 `mvn -pl platform-system-service -am -B test`（零 Docker 嵌入式 PG17，本机可跑）。**run.mjs 不存在于 acme-cloud（B0 文案有误），不得引用**；全量 mvn 交 CI。
- 口令注入载体：Spring 属性键 `acme.rls.it.role-password`（环境变量 `ACME_RLS_IT_ROLE_PASSWORD` 可覆盖）；值 = 新的测试联调值，**禁止复用旧明文 `acme_rls_app_pwd`**；Java 源不得出现口令字面量。契约/委派单禁存明文值。
- 策略表达式钉死模板（USING/WITH CHECK 同式，见确认③）；pg_policies 存储形态含 `::name`/`::text` 强转与冗余括号——`normalizePolicy` 需补 `::name` 剥离后再断言。
- V33 重放会重建旧口径策略（DROP POLICY IF EXISTS 后旧式 CREATE POLICY）：幂等重放必须按 **V33→V37 顺序**重放后断言新口径，禁止只重放 V33。
- NOLOGIN 是集群级角色属性：夹具重开 LOGIN 的时点必须在 Flyway 迁移完成后、应用 DataSource 首次使用前；禁止在迁移 SQL 内做环境分支或写死口令。
- IT 判定口径：无 Docker 自动跳过 ≠ 验证，以日志 `Tests run: N, Skipped: 0` 为准（本批 IT 全部嵌入式 PG，本机可满足）。
- AC-2 探针角色口令 = 测试内运行时生成（如 UUID 派生），非入库凭证；探针角色用后即删。
- 旧 V33 口令在 git 历史与已部署环境残留属预期，不在本批清理范围（密钥扫描移交项覆盖历史面）。

## 待确认项（随契约交用户逐项确认）

1. 确认②解释口径：迁移对 **所有环境**（prod/dev/test 库）无条件 NOLOGIN，test 的「保留 LOGIN」由 IT 夹具经配置注入重开实现。若要求迁移本身按环境分支（Flyway placeholder），prod/deploy 配置需新增占位符项、部署面变更扩大（不推荐）。
2. 新角色命名 `acme_rls_bypass`（沿 acme_ 前缀惯例）——如需改名请在确认时说明。
3. 登记（不进本批）：prod 应用连接角色 `acme` 当前为容器初始超管，superuser 天然绕过 RLS，属既有部署形态；建议后续专项收敛为最小权限角色。旁路分支收口后，`app.tenant_id` 分支仍是会话可自设变量（拥有表权限的非旁路角色可自设 tenant_id 读他租户）——V-01/决策⑥修复范围仅旁路判据，tenant_id 绑定可信主体属更深层设计，建议一并登记评估。

## 验证要求（门禁口径）

- 模块级：`mvn -pl platform-system-service -am -B test`（含既有 40+ IT 全绿）；全量 mvn 交 CI，SKIP 如实登记（本批 PG IT 嵌入式可跑，不适用 SKIP 豁免）。
- 方案 B1 验证要求落点：AC-2（自设 GUC 旁路失效）+ AC-6（迁移幂等重放）。
- 无统一 lint 脚本：验证表述 = 编译 + 测试 + `node .ai/skills/team-leader/scripts/check-contract.mjs`，禁止表述为 lint 通过。

## 流程状态（TL 每次行动后更新；唯一现场）

- phase: I-REQ            # 契约 draft 已提交，待用户逐项确认；确认后 worktree add（取号前 git fetch 重测 V37）
- round: 0
- 门禁台账：（阶段三填写）
- 委派历史：—
- 升级记录：—
