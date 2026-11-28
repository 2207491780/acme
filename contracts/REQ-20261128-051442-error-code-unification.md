---
requirementId: "REQ-20261128-051442"
title: "wire 契约·错误码统一（P1-07/P2-16）：全链路统一全码 + MSW 语义对齐 + 全码快照测试锁死"
status: confirmed          # draft | confirmed（2026-11-28 用户对话确认推荐口径，见确认记录⑦⑧；原 leader 会话分叉，主线程代转）
branchContext:
  baseBranch: acme-foundation
  mergeTarget: acme-foundation
  featureBranch: "feat/req-20261128-051442-error-code-unification"   # 取号锁：worktree.mjs add 失败=已占用，换号重试；**创建时点另见「边界与并行避让」①（须等 B3a 合流通知）**
migrations: {}             # 本批无 Flyway 迁移
apis:                      # 端点路径零变更；下列端点族仅 Result.code 值域对齐（占位防并行批次撞同域）
  - "PUT /admin/v1/system/notices/{noticeId}"
  - "PUT /admin/v1/system/notices/{noticeId}/publish"
  - "PUT /admin/v1/system/notices/{noticeId}/offline"
  - "DELETE /admin/v1/system/notices/{noticeId}"
  - "POST|PUT|DELETE /admin/v1/workflow/process-definitions**"
  - "POST|PUT|DELETE /admin/v1/workflow/instances**"
  - "POST|PUT /admin/v1/workflow/tasks**"
  - "POST|PUT /admin/v1/scheduler/jobs**"
  - "PUT /admin/v1/system/users**|tenants**|depts**|positions**|dicts**|roles**|preferences**"
  - "PUT /admin/v1/auth/profile**|password"
  - "POST /admin/v1/leave/requests**"
---

# wire 契约·错误码统一（P1-07/P2-16）：全链路统一全码 + MSW 语义对齐 + 全码快照测试锁死

## 背景

整改批次 B3b（`docs/code-review-remediation-plan-2026-11-27.md`「### B3b」节 + §9 风险项；评审证据 `contracts/REVIEW-20261127-161433.md` P1-07/P2-16）。已复核钉死事实（leader 盘点，2026-11-28）：

1. 后端 `Result.code()` 直拼全码（common-core `Result.java:52` 直取 `errorCode.code()` 无前缀剥离）；**wire 真值是异构的**，全码清单见附录 A（逐条核实于枚举源）：
   - 五域枚举（system/auth/workflow/scheduler/infra-audit 段）= `platform-xxx-NN-MMMM` 三段全码；
   - 框架层 WebError = `SYS-01-000X`、CommonError = `SYS-00-000X`（无服务前缀）；
   - InfraErrorCodes 域内分裂：audit 两码为三段全码，file/storage/job/ticket 等 12 码为**裸符号字面量**（wire 值即 `FILE_SIZE_EXCEEDED` 等字面量）。
2. 框架异常→码的固定映射（GlobalExceptionTranslator 已核实）：`@Valid`/`ConstraintViolation`/`Bind` → `SYS-01-0001`（VALIDATION_FAILED，明细 `FieldErrorDetail[]` 随 data，400）；`InvalidRequestException` → `SYS-01-0002`（BAD_REQUEST，400）；乐观锁冲突 → `SYS-01-0003`（CONCURRENCY_CONFLICT，409）。
3. 前端恒不命中（P1-07）：core `api/workflow.ts` `WORKFLOW_ERROR_CODES` 只映射 5 个 `01-xxxx` 短码（真值 14 码带 `platform-workflow-` 前缀）→ `workflowValidationErrorsOf()` 恒空；core `system-errors.ts`/`notify-errors.ts` 用符号短码键（后端不发射）→ i18n 恒兜底；web `UsersPage.tsx:167`（PASSWORD_POLICY_VIOLATED）、`TenantsPage.tsx:398,400`（TENANT_DEPTH_EXCEEDED/TENANT_CYCLE）、`ProfilePage.tsx:97,184,188`（PROFILE_INVALID/OLD_PASSWORD_INVALID/SAME_PASSWORD）、mobile `SettingsScreen.tsx:276,280`（改密两分支）对裸符号码比较 → 全部失效。注：`SESSION_NOT_FOUND`/`REVOKE_SUPERADMIN_FORBIDDEN` 在 system-errors.ts 中，但真值属 platform-auth 04 段（`platform-auth-04-0001/0002`）。
4. web MSW 全部 7 个 handler 以符号短码发 `Result.code`（`fail('NOTICE_NOT_FOUND',…)` 等 49 处去重 ~45 个符号），其中大量为后端不存在的杜撰码（PARAM_INVALID/PREFERENCE_INVALID/DICT_TYPE_HAS_ITEMS/STORAGE_IMAGE_INVALID…），逐符号→wire 真值映射见附录 B（逐条核实发射场景与后端真值）。
5. P2-16 MSW 语义漂移（已核实后端状态机）：MSW publish/offline 每次 version+1（后端 `NoticeUseCase` 幂等原状返回不写库）、publish 不扇出 sys_message（后端 `fanoutToAllEnabledUsers` 批插全员 message）、update 不拒 PUBLISHED（后端 `:79-81` 抛 NOTICE_PUBLISHED）、terminate 仅允许 RUNNING（后端 `TerminateInstanceCommand.validate:45-49` 仅拒 COMPLETED/TERMINATED，SUSPENDED 可终止）、非法态拒绝码用 DISABLED（后端真值：实例域=`platform-workflow-02-0002`、任务域=`platform-workflow-03-0002`）。
6. mobile 无 MSW mocks 目录（已核实）；web MSW 是唯一 mock 面。
7. B 端对照事实：core `file-transfer.ts` 的 TRANSFER_ERROR_MESSAGES/DIRECT_UPLOAD_ERROR_I18N_KEYS 键名与 infra 裸字面量 wire 值**已一致**（双口径并存，收敛与否见待确认项②），本批码值零改动，仅纳入快照锁定。
8. 门禁基线（B0）：前端 `CI=true pnpm typecheck && CI=true pnpm test && CI=true pnpm build`（+ desktop check）；后端 Maven 完整路径 `"C:\Program Files\Maven\apache-maven-my\bin\mvn.cmd"` + `-pl` 模块相对路径（裸名可能不可解析，备 `:artifactId`）；无统一 lint 脚本，禁止表述为 lint 通过。

## 确认记录（契约的权威解释；只存结论条目，禁存对话原文）

- [2026-11-27] ① 决策①（整改方案 §5，owner 拍板）：**全链路统一全码**——后端 `Result.code()` 与一切错误码定义/枚举零改动；前端错误模块（workflow.ts、system-errors.ts、notify-errors.ts）、i18n key、UsersPage/TenantsPage/ProfilePage/SettingsScreen 分支、MSW 码值全部升全码（「网关统一降符号码」已否决）。「全码」= 后端 `Result.code()` 实际发射值（附录 A 如实锁定，含 WebError/CommonError `SYS-xx` 段与 infra 裸字面量段，**不是**要求后端改成单一形态）。
- [2026-11-27] ② 一批内完成（方案 §9）：改动面（core 错误模块 + web 3 页面 + mobile SettingsScreen + web MSW 全 handler 码值）一批完成，避免两套码并存；P2-16 MSW 语义对齐尾随本批（MSW 必须在错误码定案后同步，否则假绿回归）。
- [2026-11-27] ③ 后端侧仅允许**新增测试文件**（枚举快照测试）；后端 main 源码零改动。
- [2026-11-28] ④ 本批无 Flyway 迁移；契约不存凭证。
- [2026-11-28] ⑤ 快照测试口径：契约附录 A 为唯一权威清单——后端枚举快照测试断言枚举常量值 == 附录 A 行；前端映射表快照测试断言映射表键集/键值 == 附录 A 对应域切片；任一侧漂移即红。**禁 MSW 假绿**：快照测试不得以 MSW 行为替代对附录 A 的静态对照。
- [2026-11-28] ⑥ 待确认项①②未获用户裁决前，对应 AC/改动面冻结不下发阶段二（其余范围不受影响）。
- [2026-11-28] ⑦ 待确认①裁决（用户确认推荐口径；原 leader 会话分叉，主线程代转）：**leave.ts 仅升码值字面**——LEAVE_TYPE_INVALID/LEAVE_REQUEST_NOT_FOUND → `SYS-01-0002`、PARAM_INVALID ×3 → `SYS-01-0001`（附录 B 行 1 泛化口径，leader 实证 LeaveRequestController `@Valid`+`@NotBlank`）；LEAVE_REQUEST_NOT_FOUND 的 HTTP 404→400 族随码值升级。语义级对齐经 leader 核实**无需随批**：B2b（56032b5）已合流 acme-foundation，leave 详情越权=查无同口径（InvalidRequestException→SYS-01-0002,400），MSW leave handler 无权限面模拟（B 端用户令牌面），码值升级后语义自然对齐，无范围扩大。
- [2026-11-28] ⑧ 待确认②裁决（用户确认推荐口径）：**DIRECT_UPLOAD_ERROR_I18N_KEYS 收敛裸码单口径**——仅删除 3 个与 wire 真值不符的键（`QUOTA_EXCEEDED`、`STORAGE_IMAGE_INVALID`、`STORAGE_TICKET_INVALID`）；`STORAGE_QUOTA_EXCEEDED`/`STORAGE_OBJECT_MISSING` 本身即附录 A.7 裸字面量 wire 真值，保留。行为不变、i18n key 不变；`file-transfer.ts` 纳入变更清单（解除冻结）。AC-10 快照口径：附录 A.7 覆盖键逐条对照；`PRESIGN_UNSUPPORTED` 为 FE 内部态键（非后端发射），快照中单独登记豁免。

## API（端点路径与后端行为零变更；本节声明 Result.code 值域契约）

- 全部端点的请求/响应 envelope、路径、HTTP 语义**不变**；唯一变化 = 前端对 `Result.code`（业务失败 ≠ `0`）的判定从符号短码升级为附录 A 全码真值。
- `ApiError.code` 来源（core `http/client.ts`）不变；业务失败提示尾部附码口径（`（${ex.code}）`）不变——升级后用户可见码自动变为全码。
- i18n key（`depts.errors.*`/`positions.errors.*`/`roles.scope.invalid`/`onlineUsers.errors.*`/`files.errors.*`）**不变**，仅映射表的键（错误码）升级。

## 验收标准（规格核心；AC 是规格+追溯锚点+覆盖率核对单元）

- AC-1 given: 后端 saveXml/deploy 校验失败发射 `Result.code=platform-workflow-01-0003`（明细随 data） when: 前端 DesignerPage/WorkflowPage 触发校验失败 then: `workflowValidationErrorsOf()` 命中并逐条提取明细（恒不命中→真命中） → 待回填
- AC-2 given: 附录 A workflow 切片（14 码） when: 断言 core `WORKFLOW_ERROR_CODES` then: 键集覆盖 01/02/03 三段语义键、每个键值 == 附录 A 全码、无短码残留（`01-0001` 形态禁止存在） → 待回填
- AC-3 given: 附录 A system/auth 切片 when: 断言 core `SYSTEM_ERROR_I18N_KEYS`/`NOTIFY_ERROR_MESSAGES` then: 键值全码化（含 auth 04/07 段跨域码如实归位）、i18n key 值不变、消息文案不变 → 待回填
- AC-4 given: 后端改密失败发射 `platform-auth-07-0001/07-0002`、租户树越界发射 `platform-system-06-0004/06-0005`、资料校验发射 `platform-system-13-0001`、建用户密码策略发射 `platform-auth-02-0002` when: UsersPage/TenantsPage/ProfilePage（web）与 SettingsScreen（mobile）对应分支判定 then: 分支全部真命中，用户可见转译文案（码尾部附全码） → 待回填
- AC-5 given: 附录 B 映射表 when: 断言 web MSW 7 个 handler 的全部业务失败发射 then: 每个 `Result.code` 值 ∈ 附录 A 且与该场景后端真值一致（含 infra 裸字面量口径）；无杜撰码残留 → 待回填
- AC-6 given: MSW notice 已是 PUBLISHED when: 再次 publish then: 原状返回（status/version/publishAt 不变、不重复扇出 message）；given: 非 PUBLISHED 时 publish then: 转 PUBLISHED + store.messages 扇出全员 message + version+1 + publishAt 刷新 → 待回填
- AC-7 given: MSW notice 非 PUBLISHED when: offline then: 幂等原状返回（version 不变）；given: PUBLISHED when: offline then: 转 OFFLINE + version+1 → 待回填
- AC-8 given: MSW notice 为 PUBLISHED when: update then: 拒绝且 `Result.code=platform-system-08-0001`（HTTP 400 族，对齐后端 BizException(SystemError.NOTICE_PUBLISHED)）；given: version 不匹配 when: update then: 拒绝且 `Result.code=SYS-01-0003`；given: 不存在的 noticeId when: delete then: 拒绝且 `Result.code=platform-system-08-0002`（对齐后端 get() orElseThrow，非幂等 empty） → 待回填
- AC-9 given: MSW 实例为 SUSPENDED when: terminate then: 成功转 TERMINATED（后端允许 SUSPENDED→TERMINATED）；given: COMPLETED/TERMINATED when: terminate/suspend/resume 非法态操作 then: 拒绝且 `Result.code=platform-workflow-02-0002`；given: 任务域非法态操作 then: `platform-workflow-03-0002`（归属不匹配=`platform-workflow-03-0003`），DISABLED 语义仅保留于流程定义域（01 段） → 待回填
- AC-10 given: 附录 A 全清单 when: 运行前端映射表快照测试 then: 四张映射表（WORKFLOW_ERROR_CODES/SYSTEM_ERROR_I18N_KEYS/NOTIFY_ERROR_MESSAGES/TRANSFER_ERROR_MESSAGES+DIRECT_UPLOAD_ERROR_I18N_KEYS）键集与键值逐条 == 附录 A 对应切片，漂移即红 → 待回填
- AC-11 given: 附录 A 全清单 when: 运行后端枚举快照测试（新增测试文件，仅 5 个 api 模块） then: SystemErrorCodes/AuthErrorCodes/WorkflowErrorCodes/SchedulerErrorCodes/InfraErrorCodes 全部常量值逐条 == 附录 A 行（InfraErrorCodes 如实锁定三段+裸字面量双口径），漂移即红 → 待回填
- AC-12 given: 既有恒不命中用例（workflowMock/workflowPages/bpmApprovalMock/页面分支用例等） when: 本批合入后全量前端门禁 then: 用例从恒不命中改为真命中断言且全绿；`CI=true pnpm typecheck && CI=true pnpm test && CI=true pnpm build`（+ desktop check）全过；后端新增快照测试模块 `mvn test` 全过 → 待回填

## 变更清单（差异契约：只写变更，不重述未变现状）

- 删除：前端全部符号短码判定（core 三模块键、页面分支字面量、MSW 杜撰码值）；无端点删除。
- 数据：无表变更（`migrations: {}`，无 Flyway）。
- 后端 main 源码：**零改动**（决策①原文）；仅新增 5 个枚举快照测试文件（platform-{system,auth,workflow,scheduler}-api + platform-infra-api，REQ 前缀命名防撞并行批）。
- 前端 core（`packages/core/src/`）：`api/workflow.ts`（WORKFLOW_ERROR_CODES 全 14 码 + 文档注释更新 + `workflowValidationErrorsOf` 不改逻辑）、`system-errors.ts`（键全码化）、`notify-errors.ts`（键全码化）；`file-transfer.ts`（仅 DIRECT_UPLOAD_ERROR_I18N_KEYS 收敛裸码单口径：删 `QUOTA_EXCEEDED`/`STORAGE_IMAGE_INVALID`/`STORAGE_TICKET_INVALID` 三键，行为不变、i18n key 不变，确认⑧）。
- 前端 web（`web/src/`）：`pages/UsersPage.tsx`、`pages/TenantsPage.tsx`、`pages/ProfilePage.tsx`（分支字面量全码化）；`pages/WorkflowPage.tsx`/`pages/workflow/DesignerPage.tsx` 经模块常量自动跟随，**不改文件**（若有裸码字面量残留则一并全码化）。
- 前端 mobile：`src/screens/SettingsScreen.tsx`（改密两分支全码化）。
- web MSW（`web/src/mocks/handlers/`）：`notify.ts`（码值 + P2-16 五项语义 + delete 幂等→NOTICE_NOT_FOUND）、`workflow.ts`（WORKFLOW_ERROR_CODES 常量引用自动全码 + 状态冲突码语义修正 + terminate 允许 SUSPENDED）、`scheduler.ts`（JOB_NOT_FOUND/KEY_DUPLICATE/KEY_IMMUTABLE → platform-scheduler 段）、`system.ts`（全部杜撰码 → 附录 B 映射）、`auth.ts`（SESSION/PASSWORD/OLD_PASSWORD/SAME_PASSWORD → platform-auth 段）、`infra.ts`（STORAGE_IMAGE_INVALID → IMAGE_INVALID；其余裸字面量已对）、`leave.ts`（LEAVE_TYPE_INVALID/LEAVE_REQUEST_NOT_FOUND → SYS-01-0002 + PARAM_INVALID ×3 → SYS-01-0001，范围见确认⑦；LEAVE_REQUEST_NOT_FOUND 的 HTTP 404→400 族）；`mocks/data.ts` 本批预期**零改动**（若实施确需触碰，仅限通知初始态，且基于 B3a 合流后基线）。
- 测试：FE 映射表快照测试（新文件）+ MSW 行为用例（AC-6~9）+ 既有恒不命中用例真命中化（AC-12）；BE 5 枚举快照测试（新文件，AC-11）。

## 附录 A：后端 Result.code 全码清单（唯一权威快照事实源，leader 逐条核实于枚举源 2026-11-28）

> 口径：后端可发射的业务/框架码全集。「HTTP」为该码登记状态码；「data」列标注明细随 data 的码。

### A.1 CommonError（common-core，SYS-00 段）
| 常量 | wire 值 | HTTP |
|---|---|---|
| NOT_FOUND | `SYS-00-0002` | 404 族 |
| INTERNAL_ERROR | `SYS-00-0006` | 500 族 |

### A.2 WebError（framework-web，SYS-01 段）
| 常量 | wire 值 | HTTP | 备注 |
|---|---|---|---|
| VALIDATION_FAILED | `SYS-01-0001` | 400 | `@Valid`/ConstraintViolation/Bind 与显式 BizException(WebError.VALIDATION_FAILED)；明细 FieldErrorDetail[] 随 data |
| BAD_REQUEST | `SYS-01-0002` | 400 | 消息不可读/类型不匹配/缺参；**InvalidRequestException 一律落此码** |
| CONCURRENCY_CONFLICT | `SYS-01-0003` | 409 | 乐观锁冲突（各 UseCase 显式抛出同码） |
| METHOD_NOT_ALLOWED | `SYS-01-0004` | 405 | |
| MEDIA_TYPE_NOT_SUPPORTED | `SYS-01-0005` | 415 | |
| MEDIA_TYPE_NOT_ACCEPTABLE | `SYS-01-0006` | 406 | |

### A.3 SystemErrorCodes（platform-system-api，`platform-system-` 前缀）
| 段 | wire 值 ← 常量 |
|---|---|
| 01 用户 | `platform-system-01-0001` USER_NOT_FOUND、`01-0002` USERNAME_DUPLICATED、`01-0003` USER_DELETE_DENIED |
| 02 角色 | `02-0001` ROLE_NOT_FOUND、`02-0002` ROLE_CODE_DUPLICATED、`02-0003` ROLE_DELETE_DENIED、`02-0004` ROLE_PARENT_CYCLE、`02-0005` ROLE_REPARENT_DENIED、`02-0006` ROLE_CODE_RESERVED、`02-0007` ROLE_GRANT_RESERVED、`02-0008` DATA_SCOPE_INVALID |
| 03 权限 | `03-0001` PERMISSION_NOT_FOUND、`03-0002` PERMISSION_CODE_DUPLICATED、`03-0003` PERMISSION_DELETE_DENIED |
| 04 菜单 | `04-0001` MENU_NOT_FOUND、`04-0002` MENU_DELETE_DENIED |
| 05 字典 | `05-0001` DICT_TYPE_NOT_FOUND、`05-0002` DICT_CODE_DUPLICATED、`05-0003` DICT_ITEM_DUPLICATED、`05-0004` DICT_TYPE_DELETE_DENIED |
| 06 租户 | `06-0001` TENANT_NOT_FOUND、`06-0002` TENANT_CODE_DUPLICATED、`06-0003` TENANT_DISABLE_DENIED、`06-0004` TENANT_DEPTH_EXCEEDED、`06-0005` TENANT_CYCLE |
| 07 鉴权 | `07-0001` PERMISSION_DENIED |
| 08 公告 | `08-0001` NOTICE_PUBLISHED、`08-0002` NOTICE_NOT_FOUND |
| 09 站内信 | `09-0001` MESSAGE_NOT_FOUND、`09-0002` MESSAGE_FORBIDDEN |
| 10 部门 | `10-0001` DEPT_NOT_FOUND、`10-0002` DEPT_HAS_CHILDREN、`10-0003` DEPT_HAS_USERS、`10-0004` DEPT_PARENT_CYCLE |
| 11 岗位 | `11-0001` POSITION_NOT_FOUND、`11-0002` POSITION_IN_USE、`11-0003` POSITION_CODE_DUPLICATED |
| 12 系统配置 | `12-0001` SYSTEM_CONFIG_INVALID、`12-0002` SYSTEM_WATERMARK_TEMPLATE_INVALID |
| 13 资料 | `13-0001` PROFILE_INVALID |
| 14 租户套餐 | `14-0001` TENANT_PACKAGE_DUPLICATE、`14-0002` TENANT_PACKAGE_NOT_FOUND、`14-0003` TENANT_PACKAGE_IN_USE、`14-0004` TENANT_PACKAGE_MENU_INVALID、`14-0005` TENANT_PACKAGE_MENU_FORBIDDEN、`14-0006` TENANT_CAPABILITY_INVALID |
| 15 租户模板 | `15-0001` TENANT_TEMPLATE_NOT_FOUND、`15-0002` TENANT_TEMPLATE_DUPLICATE、`15-0003` TENANT_TEMPLATE_CONTENT_INVALID |

### A.4 AuthErrorCodes（platform-auth-api，`platform-auth-` 前缀）
| 段 | wire 值 ← 常量 |
|---|---|
| 01 会话 | `01-0001` LOGIN_FAILED、`01-0002` ACCOUNT_LOCKED、`01-0003` ACCOUNT_DISABLED、`01-0004` LOGOUT_FAILED |
| 02 凭证 | `02-0001` CREDENTIAL_NOT_INITIALIZED、`02-0002` PASSWORD_POLICY_VIOLATED |
| 03 验证码/令牌 | `03-0001` CAPTCHA_SEND_LIMITED、`03-0002` CAPTCHA_INVALID、`03-0003` CAPTCHA_EXPIRED、`03-0004` CONSUMER_DISABLED、`03-0005` REFRESH_TOKEN_INVALID |
| 04 在线会话 | `04-0001` SESSION_NOT_FOUND、`04-0002` REVOKE_SUPERADMIN_FORBIDDEN |
| 05 租户切换 | `05-0001` TENANT_SWITCH_FORBIDDEN、`05-0002` TENANT_SWITCH_FAILED |
| 06 扫码 | `06-0001` QR_TICKET_LIMITED、`06-0002` QR_TICKET_EXPIRED、`06-0003` QR_TICKET_CANCELLED、`06-0004` QR_TICKET_INVALID |
| 07 改密 | `07-0001` OLD_PASSWORD_INVALID、`07-0002` SAME_PASSWORD |

### A.5 WorkflowErrorCodes（platform-workflow-api，`platform-workflow-` 前缀）
| 段 | wire 值 ← 常量 |
|---|---|
| 01 流程定义 | `01-0001` WORKFLOW_KEY_DUPLICATE、`01-0002` WORKFLOW_XML_INVALID、`01-0003` WORKFLOW_VALIDATION_FAILED（明细随 data）、`01-0004` WORKFLOW_DEFINITION_NOT_FOUND、`01-0005` WORKFLOW_DEFINITION_DISABLED |
| 02 流程实例 | `02-0001` WORKFLOW_INSTANCE_NOT_FOUND、`02-0002` WORKFLOW_INSTANCE_STATE_CONFLICT、`02-0003` WORKFLOW_DEFINITION_NOT_DEPLOYED、`02-0004` WORKFLOW_BUSINESS_KEY_REQUIRED |
| 03 任务 | `03-0001` WORKFLOW_TASK_NOT_FOUND、`03-0002` WORKFLOW_TASK_STATE_CONFLICT、`03-0003` WORKFLOW_ASSIGNEE_MISMATCH、`03-0004` WORKFLOW_DELEGATE_STATE_INVALID、`03-0005` WORKFLOW_NO_ASSIGNEE_RESOLVED |

### A.6 SchedulerErrorCodes（platform-scheduler-api，`platform-scheduler-` 前缀）
| 段 | wire 值 ← 常量 |
|---|---|
| 01 任务 | `01-0001` SCHEDULER_JOB_KEY_DUPLICATE、`01-0002` SCHEDULER_JOB_NOT_FOUND、`01-0003` SCHEDULER_JOB_KEY_IMMUTABLE、`01-0004` SCHEDULER_TRIGGER_INVALID、`01-0005` SCHEDULER_RETRY_POLICY_INVALID |
| 02 执行 | `02-0001` SCHEDULER_EXECUTION_NOT_FOUND、`02-0002` SCHEDULER_EXECUTION_STATE_CONFLICT |

### A.7 InfraErrorCodes（platform-infra-api，**域内分裂如实锁定**）
| wire 值 ← 常量 |
|---|
| `platform-infra-01-0001` AUDIT_EVENT_INVALID、`platform-infra-01-0002` AUDIT_STORE_FAILED |
| 裸字面量段（wire 值=常量名字面量）：`FILE_SIZE_EXCEEDED`、`FILE_TYPE_REJECTED`、`FILE_NOT_FOUND`、`EXCEL_PARSE_FAILED`、`JOB_ALREADY_RUNNING`、`JOB_NOT_FOUND`、`STORAGE_QUOTA_EXCEEDED`、`IMAGE_INVALID`、`TICKET_INVALID`、`SIZE_MISMATCH`、`STORAGE_OBJECT_MISSING`、`OP_EXCEPTION_NOT_FOUND` |

## 附录 B：web MSW 符号码 → wire 真值映射（leader 逐条核实发射场景与后端真值）

> 前提：MSW 只许跟随后端 wire 真值，不许反向定义契约。实施时若发现本表未覆盖的发射点：就地停止上报，禁止猜测。

| MSW 现发码（handler） | wire 真值 | 场景语义备注 |
|---|---|---|
| `PARAM_INVALID`（system/auth 等，~36 处） | `SYS-01-0001` | DTO 校验失败；明细随 data（MSW fail 需带 data 明细处用 failWithData 形态） |
| `BAD_REQUEST`（system） | `SYS-01-0002` | |
| `VERSION_CONFLICT`（system/dept/dict） | `SYS-01-0003` | 并发冲突 |
| `PREFERENCE_INVALID`（system preferences） | `SYS-01-0001` | UserPreferenceUseCase 显式抛 VALIDATION_FAILED |
| `DEPT_NAME_INVALID` | `SYS-01-0001` | DTO @Valid（名称非空） |
| `DEPT_PARENT_INVALID` | 父不存在=`platform-system-10-0001`（DEPT_NOT_FOUND）；成环=`10-0004`（DEPT_PARENT_CYCLE） | DeptUseCase.get(targetParentId).orElseThrow 实证 |
| `DEPT_NOT_FOUND`/`DEPT_HAS_CHILDREN`/`DEPT_HAS_USERS` | `platform-system-10-0001/0002/0003` | 直映 |
| `DICT_TYPE_NOT_FOUND` | `platform-system-05-0001` | |
| `DICT_TYPE_HAS_ITEMS` | `platform-system-05-0004`（DICT_TYPE_DELETE_DENIED） | 后端「存在字典项拒绝删除」即此码 |
| `DICT_ITEM_NOT_FOUND` | 条目父不存在=`SYS-01-0001`；字典项删除为幂等软删（不存在=成功原状） | DictUseCase:141 实证 |
| `DATA_SCOPE_INVALID` | `platform-system-02-0008` | |
| `USER_NOT_FOUND`/`ROLE_NOT_FOUND` | `platform-system-01-0001`/`02-0001` | |
| `POSITION_NOT_FOUND`/`POSITION_IN_USE` | `platform-system-11-0001`/`11-0002` | |
| `TENANT_NOT_FOUND`/`TENANT_DEPTH_EXCEEDED`/`TENANT_CYCLE` | `platform-system-06-0001/0004/0005` | |
| `TENANT_CODE_INVALID` | 格式非法=`SYS-01-0001`；重复=`platform-system-06-0002`（TENANT_CODE_DUPLICATED） | 按发射场景归类 |
| `TENANT_PACKAGE_*`（NOT_FOUND/DUPLICATE/IN_USE/MENU_INVALID/CAPABILITY_INVALID） | `platform-system-14-0002/0001/0003/0004/0006` | 直映 |
| `TENANT_TEMPLATE_NOT_FOUND`/`TENANT_TEMPLATE_DUPLICATE` | `platform-system-15-0001/0002` | 直映 |
| `WATERMARK_TEMPLATE_INVALID` | `platform-system-12-0002` | |
| `PROFILE_INVALID` | `platform-system-13-0001` | |
| `NOTICE_NOT_FOUND`/`NOTICE_PUBLISHED` | `platform-system-08-0002`/`08-0001` | publish 拒绝场景 HTTP 族对齐后端 BizException 语义（400 族） |
| `SESSION_NOT_FOUND`（auth） | `platform-auth-04-0001` | |
| `PASSWORD_POLICY_VIOLATED` | `platform-auth-02-0002` | |
| `OLD_PASSWORD_INVALID`/`SAME_PASSWORD` | `platform-auth-07-0001`/`07-0002` | |
| `JOB_NOT_FOUND`（scheduler） | `platform-scheduler-01-0002`（SCHEDULER_JOB_NOT_FOUND） | scheduler 域，非 infra 裸码 |
| `KEY_DUPLICATE`/`KEY_IMMUTABLE`（scheduler） | `platform-scheduler-01-0001`/`01-0003` | |
| workflow handler 全部 `WORKFLOW_ERROR_CODES.*` | 模块升级后自动全码（附录 A.5） | 状态冲突语义修正见 AC-9 |
| `FILE_*`/`TICKET_INVALID`/`SIZE_MISMATCH`/`STORAGE_QUOTA_EXCEEDED`/`STORAGE_OBJECT_MISSING`/`OP_EXCEPTION_NOT_FOUND`/`EXCEL_PARSE_FAILED`（infra） | 裸字面量（**已是 wire 真值，零改动**） | 附录 A.7 |
| `STORAGE_IMAGE_INVALID`（infra） | `IMAGE_INVALID`（裸字面量） | 唯一 infra 不匹配项 |
| `LEAVE_TYPE_INVALID`/`LEAVE_REQUEST_NOT_FOUND`（leave） | `SYS-01-0002` | InvalidRequestException→WebError.BAD_REQUEST 实证；范围见待确认项① |

## 边界与并行避让

1. **worktree 创建与开发开工待 B3a 合流**：B3a（REQ-20261127-234158，wire DTO 对齐）正在 worktree 开发前端（mobile auth 字段、core scheduler/system 类型、web JobsPage/MenusPage/nav/RolesPage/TenantPackagesPage、MSW scheduler+system handlers、mocks/data.ts，未合流）。本批与其目录交集大（core、web pages、MSW handlers、data.ts）但文件级近零重叠——唯一同文件交叠：`mocks/handlers/scheduler.ts`（B3a 改字段名、本批改码值）与 `mocks/handlers/system.ts`（B3a 改 AdminMenuView 消费点、本批改 fail 码值）。**本批 worktree add 与 spawn 等主线程「B3a 已合流」通知后方可执行**，基于合流后基线开发。（前置已满足：B3a 已于 64ef965 合流 acme-foundation，本批基于合流后基线 e6c0c59 开发。）
2. B1/B2a/B2b 批占用文件禁改：platform-system RLS 相关（B1）、framework-feign+idempotent+scheduler 控制器+workflow 客户端（B2a）、business-leave+platform-auth-service 后端 main 源码（B2b）。本批后端侧唯一触碰 = 新增 5 个 api 模块枚举快照测试文件（REQ 前缀命名，如 `REQ20261128051442SystemErrorCodesSnapshotTest.java`）。
3. 后端 `Result.code()` 与一切错误码定义/枚举 **零改动**（决策①）；发现「必须改后端才能统一」的情形 = 停止上报，禁止擅动。
4. 本批禁改：`.ai/`、`.ai-memory/`、`docs/` 整改方案（状态勾销由主线程按 §8.5 执行）、`packages/core/src/api/{auth,scheduler,system}.ts` 的类型声明（B3a 范围）。
5. 子代理禁一切 git 操作；契约歧义就地停止上报，禁止猜测。
6. 本批无 Flyway 迁移；契约与委派单禁存凭证。

## 待确认项（已全部裁决：见确认记录⑦⑧，2026-11-28 用户确认推荐口径；下文保留裁决前原文存档）

1. **leave.ts MSW 码值升级范围**：LEAVE_TYPE_INVALID/LEAVE_REQUEST_NOT_FOUND 的后端真值 = `SYS-01-0002`（leave 域 `InvalidRequestException`→WebError.BAD_REQUEST，无专用 404 码）；但 leave 域后端语义归 B2b 占用改造中（P1-05/P1-08 权限三态可能新增 403/404 语义）。**建议**：本批仅升码值字面至 `SYS-01-0002`（对齐当前 main 真值），leave 语义级对齐（状态码/新码）留 B2b 合流后跟随批处理。
2. **DIRECT_UPLOAD_ERROR_I18N_KEYS 双口径收敛**：core `file-transfer.ts` 直传映射键同时收录 `STORAGE_` 前缀与裸码两套（后端 wire=裸码）。**建议**：收敛为裸码单口径（行为不变、防未来漂移；MSW infra.ts `STORAGE_IMAGE_INVALID` 同步改 `IMAGE_INVALID`，已在变更清单内）。保留双口径亦无害——二选一请裁决。

## 流程状态（TL 每次行动后更新；这是唯一现场，跨会话据此恢复）

- phase: I-DONE           # I-REQ | I-DONE | II-DEV | III-GATE | III-TEST | III-REVIEW | DONE
- round: 0
- worktree: 已创建 `C:/MyWorkSpace/ai-coding-worker/.worktrees/feat-req-20261128-051442-error-code-unification`（分支 feat/req-20261128-051442-error-code-unification，基点 acme-foundation fa1a5d0=确认态契约入库点；三路工人共用此工作树，文件面互斥见委派单）
- 门禁台账：
  - （未进入阶段三）
- 委派历史：
  - 2026-11-28 三路委派单（frontend-dev / backend-dev / tester）随 phase=I-DONE 交主线程 spawn；受影响既有 web tests 清单按主线现状（B3a 64ef965 / B2b 56032b5 合流后）重盘：schedulerMswContract:138/148、tenantInitTemplateMsw:243/292/295/303、profile:251、adminFrontendPages:290、directUpload:272、workflowMock(102730):390、mobile settingsProfile:379；core adminLeaveApi/ACCOUNT_DISABLED/CONCURRENT_MODIFICATION/LEAVE_PERIOD_INVALID/AUTH_DOWNSTREAM 经核实为非比较面（stub 自洽或泛化路径），不在升级面。
- 升级记录：
  - （无）
