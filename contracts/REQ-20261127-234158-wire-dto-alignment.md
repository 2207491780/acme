---
requirementId: "REQ-20261127-234158"
title: "wire 契约·DTO 字段对齐（P1-06/P1-10/P2-02）：后端序列化为权威修前端声明并补真实序列化契约测试"
status: confirmed          # 2026-11-28 用户逐项确认（见确认记录⑥）；此前 draft 已入库 ecdb2ff
branchContext:
  baseBranch: acme-foundation
  mergeTarget: acme-foundation
  featureBranch: "feat/req-20261127-234158-wire-dto-alignment"
migrations: {}             # 本批无 Flyway 迁移
apis:                      # 端点路径不变，仅下列端点的 wire 载荷/视图字段名与 TS 类型对齐（占位防并行批次撞同域）
  - "POST /app/v1/auth/tokens"
  - "POST /app/v1/auth/tokens/refresh"
  - "POST /admin/v1/scheduler/jobs"
  - "PUT /admin/v1/scheduler/jobs/{jobId}"
  - "GET /admin/v1/system/menus"
  - "POST /admin/v1/system/menus"
  - "PUT /admin/v1/system/menus/{menuId}"
---

# wire 契约·DTO 字段对齐（P1-06/P1-10/P2-02）

## 背景

整改批次 B3a（对应 `docs/code-review-remediation-plan-2026-11-27.md`「### B3a」节 + §9；评审证据 `contracts/REVIEW-20261127-161433.md` P1-06/P1-10/P2-02）。已复核钉死事实：

1. 后端无任何 `@JsonProperty`/`@JsonAlias`/NamingStrategy/MixIn——**序列化即契约声明**。owner 决策④：后端现有 wire 为权威修前端，禁加别名固化错误契约。
2. `AppTokenIssued`（platform-auth-api `api/dto/AppTokenIssued.java:15-17`）record 组件为 `accessExpiresAt`/`refreshExpiresAt`（Instant）/`userId`（Long）；mobile 四处却声明并消费 `expiresAt` → `Date.parse(undefined)=NaN`，过期判定/刷新轮换全错。
3. `JobSaveRequest`/`JobView`（platform-scheduler-service `application/`）字段为 `cronExpr`/`fixedRateMs`；FE `packages/core/src/api/scheduler.ts` 与 web MSW 均用短名 `cron`/`fixedRate` → CRON/FIXED_RATE 任务创建/编辑丢字段。
4. `AdminMenuView`（platform-system-api `api/dto/AdminMenuView.java:12-14`）`id`/`parentId`/`version` 均 Long；`LongToStringModule`（common-serialization）将 Long 序列化为 JSON 字符串（根节点 `parentId=0` → wire `"0"`），FE 却声明 `number`（同文件自述 wire 字符串，自相矛盾）。
5. 真实序列化测试先例：`platform-system-api` 的 `SystemApiContractTest`（`JsonModules.standard()` 构 mapper、断言 `writeValueAsString` 产物），该模块 pom 已有 test-scope `common-serialization`；platform-scheduler-service 经 framework-jackson→common-serialization 传递可用；**platform-auth-api pom 无 common-serialization**（见待确认项①）。
6. B 端 `AdminTokenIssued` 后端字段名本就是 `expiresAt`——mobile `bSessionStore` 及 B 端相关 mock/用例的 `expiresAt` 是正确契约，**禁顺手改**。
7. 门禁基线：后端集成测试已零 Docker 嵌入式化（8e717ef），后端测试须实际本机运行；无统一 lint 脚本，禁表述为 lint 通过。

## 确认记录（契约的权威解释；只存结论条目，禁存对话原文）

- [2026-11-27] ① 决策④（整改方案 §5）：以**后端现有 wire 序列化为权威**修前端；不用 `@JsonProperty` 别名、不加 `@JsonAlias` 过渡（未来出现旧客户端兼容需求再评估）。
- [2026-11-27] ② 批次范围 = B3a 三项（P1-06/P1-10/P2-02）；验证口径 = mobile/web typecheck + 测试 + build + 契约测试；契约测试必须断言真实 Java DTO 序列化产物（或等价 golden），**禁 MSW 假绿**（§9 风险项）。
- [2026-11-27] ③ B 端 `AdminTokenIssued.expiresAt` 为正确契约，不进本批改动面（含其 mock/测试）。
- [2026-11-27] ④ 本批无 Flyway 迁移；错误码全码统一属 B3b 独立后批，本批禁顺手改任何错误码映射/断言（契约测试不含错误码断言）。
- [2026-11-27] ⑤ 待证事项（见「待确认项」）未获用户确认前，对应 AC 冻结不下发阶段二。
- [2026-11-28] ⑥ 用户对话指示「继续」（与方案决策④一致）：待确认项①=**案A**（platform-auth-api `pom.xml` 加 test-scope 依赖 common-serialization + 新增独立 wire 测试文件，冲突面最小）；②=`JobAdminView` 额外漂移（createdAt/shardable/remark）**仅登记整改方案，本批不顺手修**，AC-5 冻结解除。

## API（端点路径零变更；仅字段级对齐，后端 Java DTO 一律不动）

### POST /app/v1/auth/tokens 与 /tokens/refresh（P1-06）
- wire（权威）：`accessToken`/`refreshToken`/`accessExpiresAt`(ISO-8601)/`refreshExpiresAt`(ISO-8601)/`userId`(JSON 字符串，LongToStringModule)/`phone`。
- FE 修复：mobile `AppTokenIssued` 接口 `expiresAt` → `accessExpiresAt`；消费点 `store/authStore.ts`（renewed）、`api/client.ts`（刷新 envelope）、`screens/LoginScreen.tsx`（登录/注册两处）同步；`userId: string` 声明保持。

### POST/PUT /admin/v1/scheduler/jobs（P1-10）
- wire（权威）：请求 `JobSaveRequest.cronExpr`(CRON 必填)/`fixedRateMs`(FIXED_RATE 必填，Long 毫秒)；视图 `JobView.cronExpr`/`fixedRateMs`（`id`/`version` wire 字符串）。
- wire 类型事实（LongToStringModule 全局生效，SystemApiContractTest 先例）：视图侧一切 Long 字段 wire 均为 JSON 字符串——`fixedRateMs`/`timeoutMs`/`version` 字符串、`shardTotal`（Integer）数值；保存载荷侧后端反序列化接受 number 与 string 双口径（LongToStringSerializationTest ③），FE 载荷保持发 number、视图字段声明 `string | null`。
- FE 修复：`packages/core/src/api/scheduler.ts`（JobAdminView/JobSavePayload/JobUpdatePayload）、web `pages/JobsPage.tsx`、MSW `mocks/handlers/scheduler.ts` + `mocks/data.ts` 同步改长名（MSW 只许跟随后端 golden，不许反向定义契约）。

### GET/POST/PUT /admin/v1/system/menus（P2-02）
- wire（权威）：`id`/`parentId`/`version` 均 JSON 字符串；根节点 `parentId` 为 `"0"`（非 null）；`children` 嵌套。
- FE 修复：`packages/core/src/api/system.ts` `AdminMenuView.id/parentId` 改 `string`、根判定 `'0'`；`menuCreate` 的 `parentId?: string | null`、`menuUpdate/menuDelete` 的 `menuId: string`；消费方（web `nav.tsx`/`MenusPage.tsx`/`RolesPage.tsx`/`TenantPackagesPage.tsx`、`mocks/handlers/system.ts`、`mocks/data.ts` 菜单树字面量）同步；typecheck 暴露的其余消费方一并修。

### 契约测试（三类 wire golden，禁 MSW）
- Java 侧（真实 `JsonModules.standard()` mapper 断言 `writeValueAsString`/`readValue` 产物，golden 内联锁字段名与字符串化行为）：platform-scheduler-service（JobSaveRequest 反序列化 + JobView 序列化）、platform-system-api（AdminMenuView 树序列化）、platform-auth-api（AppTokenIssued 序列化，落点待确认项①）。
- TS 侧（非 MSW 纯数据对照测试）：与 Java golden 字节等价的 fixture 逐键断言 TS 接口键名/类型一致（`satisfies` 编译期 + 运行期键集断言），任一侧漂移即红；fixture 头注释交叉引用 Java 测试类为事实源。

## 验收标准

- AC-1 given: mobile 发起 C 端验证码登录（POST /app/v1/auth/tokens 真实 wire 含 `accessExpiresAt`） when: `LoginScreen` 构造会话 then: `accessExpiresAtMs = Date.parse(issued.accessExpiresAt)` 为有限正数（非 NaN），代码与 mock 均不再引用 `expiresAt`（C 端） → mobile/tests/REQ20261127234158-sessionExpiryFlow.test.ts::should_parse_access_expires_at_wire_key_to_finite_positive_session_ms_when_c_side_login_succeeds（+负例 should_yield_non_finite_session_expiry_when_wire_fixture_uses_legacy_expires_at_key）
- AC-2 given: C 端会话存在且 refresh 令牌未本地过期 when: `client.ts` 静默刷新（POST /app/v1/auth/tokens/refresh） then: 刷新 envelope 按 `accessExpiresAt` 解析、`authStore.renewed()` 落库为有限值，轮换链路可完成 → mobile/tests/REQ20261127234158-sessionExpiryFlow.test.ts::should_renew_session_with_wire_expiry_ms_when_access_token_rotated_after_401
- AC-3 given: `AppTokenIssued` Java DTO 经 `JsonModules.standard()` mapper 序列化 when: 断言产物 then: `userId` 为 JSON 字符串（如 `"1234567890123456789"` 带引号）、字段名为 `accessExpiresAt`/`refreshExpiresAt`，与 mobile `AppTokenIssued`（`userId: string`）声明经 TS 对照测试锁定一致 → platform-auth-api REQ20261127234158AppTokenIssuedWireTest::appTokenIssuedSerializesAccessRefreshExpiresAtNamesAndStringUserId + mobile/tests/REQ20261127234158-authWireContract.test.ts::should_carry_stringified_snowflake_user_id_not_json_number
- AC-4 given: web 新建/编辑 CRON 与 FIXED_RATE 任务 when: `JobsPage` 提交 `JobSavePayload`/`JobUpdatePayload` then: 载荷字段为 `cronExpr`/`fixedRateMs`（不再出现 `cron`/`fixedRate`），MSW handler/data 同步长名，创建/编辑后 mock 任务回读字段非空 → web/tests/REQ20261127234158-jobsCronEdit.test.tsx::should_post_long_name_cron_expr_payload_without_short_names_when_cron_job_created 与 ::should_post_fixed_rate_ms_number_payload_without_short_names_when_fixed_rate_job_created（适配件 web/tests/REQ20261122023213-schedulerMswContract.test.ts 辅助）
- AC-5 given: 后端 `JobView` 真实序列化 when: FE `JobAdminView` 声明对照 then: `cronExpr`/`fixedRateMs` 键名一致、`id`/`version`/`fixedRateMs`/`timeoutMs` 视图声明为 `string`（wire Long→字符串）、`shardTotal` 保持 number；`JobsPage` 渲染与编辑回填同步改名（回填做 `Number(record.fixedRateMs)` 换算） → platform-scheduler-service REQ20261127234158SchedulerJobWireTest::jobViewSerializesLongFieldsAsJsonStringsAndKeepsShardTotalNumeric + web/tests/REQ20261127234158-jobsCronEdit.test.tsx::should_backfill_numeric_fixed_rate_from_wire_string_and_submit_number_when_fixed_rate_row_edited + web/tests/REQ20261127234158-schedulerMenuWireContract.test.ts::should_stringify_long_fields_and_keep_shard_total_numeric_in_view_wire
- AC-6 given: platform-scheduler-service 测试目录新增 wire 测试（真实 mapper，非 MSW） when: mvn 测试运行 then: 含 `cronExpr`/`fixedRateMs` 的 JSON 反序列化进 `JobSaveRequest` 成功、短名 JSON 不映射（`cronExpr` 为 null），`JobView` 序列化 golden 锁定字段名与 `id`/`version` 字符串化 → REQ20261127234158SchedulerJobWireTest 三例：jobViewSerializesLongFieldsAsJsonStringsAndKeepsShardTotalNumeric / jobSaveRequestDeserializesLongNameFieldsAcceptingNumberAndString / jobSaveRequestShortNameJsonIsIgnoredLeavingLongNameFieldsNull（实测=忽略非 fail-fast，已注明）
- AC-7 given: /admin/v1/system/menus 真实 wire（`id`/`parentId`/`version` 字符串、根 `parentId="0"`） when: web 菜单树渲染与增删改 then: `AdminMenuView.id/parentId` 声明 `string`、根判定 `parentId === '0'`、`menuCreate.parentId?: string | null`、`menuUpdate/menuDelete` 的 `menuId: string`，页面/导航/套餐过滤功能不回归 → web/tests/REQ20261127234158-menusTreeStringIds.test.tsx 四例（should_render_wire_menu_tree_with_string_ids_when_root_parent_id_is_zero_string / should_send_null_parent_id_when_root_menu_created / should_preserve_string_parent_id_when_child_menu_created / should_carry_string_menu_id_in_path_when_menu_updated_and_deleted）+ web/tests/REQ20261127234158-schedulerMenuWireContract.test.ts::should_stringify_id_parent_id_version_and_root_parent_id_as_zero_string
- AC-8 given: platform-system-api 测试新增 `AdminMenuView` 序列化测试（`SystemApiContractTest` 先例口径） when: 序列化含根+子节点的树 then: `id`/`parentId`/`version` 均为 JSON 字符串、根 `parentId="0"`、`children` 嵌套保留 → platform-system-api REQ20261127234158AdminMenuViewWireTest::adminMenuViewTreeSerializesLongIdsAsStringsAndKeepsChildrenNesting
- AC-9 given: 三组 Java golden（AppTokenIssued / JobView+JobSaveRequest / AdminMenuView）与对应 FE 非 MSW fixture when: 逐键对照断言 then: 键名与类型全一致、测试全绿；golden 重复件头注释交叉引用 Java 测试类 → Java 侧三件 REQ20261127234158*WireTest（AppTokenIssued/AdminMenuView/SchedulerJob）+ FE 侧 mobile/tests/REQ20261127234158-authWireContract.test.ts 与 web/tests/REQ20261127234158-schedulerMenuWireContract.test.ts（golden 片段同源、登记漂移键 param/retryPolicy 显式豁免类型对照）
- AC-10 given: 全部改名完成后 when: `CI=true pnpm typecheck && CI=true pnpm test && CI=true pnpm build`（web/mobile，desktop `pnpm --filter @acme/desktop check`）+ 受影响后端模块 `mvn test` then: 全绿；B 端 `AdminTokenIssued.expiresAt` 相关既有用例保持通过不受波及 → frontend-dev 四道自查全绿（B 端 qrLogin 等既有用例全分片 test 通过）+ backend-dev 三模块 mvn 判定行全绿；主线程合并前复核重跑兜底

## 变更清单（差异契约）

- 删除：无端点删除；仅删除 FE 错误字段声明（C 端 `AppTokenIssued.expiresAt`；scheduler `cron`/`fixedRate` 短名）。
- 数据：无表变更（`migrations: {}`，无 Flyway）。
- 前端：
  - mobile：`src/api/auth.ts`、`src/api/client.ts`、`src/store/authStore.ts`、`src/screens/LoginScreen.tsx` 字段改名；受影响 tests（C 端 mock 改 `accessExpiresAt`；B 端 `expiresAt` 用例不动）。
  - core/web：`packages/core/src/api/scheduler.ts`、`packages/core/src/api/system.ts`（AdminMenuView 类型）、`web/src/pages/JobsPage.tsx`、`web/src/pages/MenusPage.tsx`、`web/src/nav.tsx`、`web/src/pages/RolesPage.tsx`、`web/src/pages/TenantPackagesPage.tsx`、`web/src/mocks/handlers/{scheduler,system}.ts`、`web/src/mocks/data.ts`（菜单/scheduler 字面量改字符串/长名）；受影响 tests（schedulerAdminPage、schedulerMswContract、loggingJobs、nav、navMenuLeaves、packagesPage、capabilityForm 等）。
  - 新增 FE 非 MSW wire 对照测试（mobile/web 各自 fixture）。
- 后端（**仅测试侧，新增文件**；不改任何 main 源码）：platform-scheduler-service wire 测试、platform-system-api AdminMenuView wire 测试、platform-auth-api AppTokenIssued wire 测试（落点待确认项①）。
- MSW：web mocks scheduler 字段名同步长名——MSW 只许跟随后端 golden，不许反向定义契约。

## 边界与并行避让

- 禁改占用文件：B1（platform-system RLS 相关）、B2a（platform-scheduler 控制器与 workflow 客户端、framework-idempotent）、B2b（business-leave / platform-auth / 网关适配层）批次既有占用文件。
- 本批后端改动仅限**新增测试文件**；唯一直接触碰占用模块的例外是待确认项①裁决的 platform-auth-api `pom.xml` 一行 test 依赖，未裁决前不得执行。
- 本批**禁改**任何错误码映射（B3b 独立后批）、**禁改**任何后端 main 源码、**禁做** git merge/push/删分支。
- 发现所需触碰文件与并行批次冲突：就地停止上报，禁止猜测。

## 待确认项（均已于 2026-11-28 裁决，见确认记录⑥）

1. **P1-06 后端 golden 测试落点** → 已裁决：**案A**——`platform-auth-api/pom.xml` 增一行 test-scope 依赖 `common-serialization` + 新增独立 wire 测试文件（REQ 前缀命名防撞 B2b）。
2. **`JobAdminView` 额外漂移** → 已裁决：`createdAt`（后端无此字段）/`shardable`/`remark`（后端有 FE 未声明）**仅登记整改方案，本批不改**；AC-5 断言范围不变（仅 cronExpr/fixedRateMs 键名与 id/version 字符串化）。[2026-11-28 补登记同类] `param`/`retryPolicy`：后端为 jsonb 文本 String（原样透传），FE 声明 object（Record/JobRetryPolicy）——同属未列漂移，按②口径仅登记不改。
3. 错误码边界已按确认记录④冻结，无需再议。

## 流程状态（TL 每次行动后更新；这是唯一现场，跨会话据此恢复）

- phase: III-REVIEW       # I-REQ | I-DONE | II-DEV | III-GATE | III-TEST | III-REVIEW | DONE
- round: 0
- worktree: `.worktrees/feat-req-20261127-234158-wire-dto-alignment`（分支 feat/req-20261127-234158-wire-dto-alignment，基点=确认入库 667c511；**交付 29 文件未提交**，由主线程统一 commit/合并）
- 门禁台账（2026-11-28 首轮，leader 逐文件核验 + 工人自验出处）：
  - 1 三向一致: PASS（端点路径零变更；键链路四点核对——FE JobSavePayload↔JobSaveRequest↔双侧 wire 测试、mobile 刷新 envelope↔AppTokenIssued↔golden、AdminMenuView↔golden、B 端 AdminTokenIssued.expiresAt 未动）
  - 2 AC 覆盖率: PASS（AC-1~10 各 ≥1 用例，映射已回填验收标准节；tester-be 映射与 delivered 文件逐一核对一致）
  - 3 契约机器层: PASS（[GATE] name=contract-valid result=PASS）
  - 4 本地测试: PASS（出处=工人自验记录，主线程合并前复核重跑兜底）——backend-dev 三模块 mvn 绿（platform-auth-api/platform-system-api/platform-scheduler-service，判定行 Tests run: N, Failures: 0, Errors: 0, Skipped: 0；短名负例实测=忽略非 fail-fast）；frontend-dev 四道 CI=true 全绿（typecheck/全分片 test/build/desktop check）；tester-fe 白名单两轮绿（fetch-stub 直注非 MSW + RN 宿主替身装配）
  - 5 跨需求冲突: PASS（B1 apis=[]；B2a internal 域异路径；B2b leave 域无交叠；B3b `/admin/v1/scheduler/jobs**` 路径族与本法相交但改动轴不同【错误码值域 vs 载荷字段名】——**合并顺序约束：B3a 先合，B3b rebase 吸收**，无文件级并发冲突；migrations 双方不冲突）
- 委派历史：
  - frontend-dev: 交付（mobile 四处改名/B 端未动、core/web 类型与 MSW 同步、7+1 适配件、2 个非 MSW wire 对照测试；四道自查全绿）
  - backend-dev: 交付（三件 wire 测试 + auth-api pom 案A 一行依赖；零 main 改动；enforcer 通过；短名负例实测结论已注明）
  - tester-be: 交付（AC-3/6/8/9 映射表 + 断言点核对清单，含「golden 禁整串 equals 防已登记漂移字段爆红」口径——delivered 文件核实均 per-field contains + 豁免键显式化）
  - tester-fe: 交付（3 用例文件 10 例；AMB 假设经自验落定；白名单内两轮全绿）
- 升级记录：暂无
