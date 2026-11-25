# ACME 多租户机制调查汇总（2026-11-24）

> 只读调查；详细一手证据见 `.ai-tmp/tenant-a-model.md`、`.ai-tmp/tenant-b-isolation.md`、`.ai-tmp/tenant-c-creation.md`（临时件，成熟后本稿提炼保留、临时件可删）。

## 1. 总体设计
- **单库共享 schema + 行级隔离**：租户归属表带 `tenant_id varchar(64)` 列，存 **tenant_code 字符串**（非雪花 id）；平台目录表（sys_tenant/sys_permission/sys_menu/sys_tenant_package/sys_tenant_init_template）无 tenant_id、不过滤（Q-D04）。
- 租户实体 `SysTenant`（platform-system-service `domain/`，表 sys_tenant）：tenant_code(uk)/tenant_name/status(ENABLED|DISABLED)/contact/expires_at/package_id/grace_until/parent_id/level（层级上限 3）。迁移 V1/V17/V24/V30/V31/V32 演进。
- 生命周期＝**双物化态 + 读时派生四视觉态**（启用中/宽限期/已过期/已停用正交，停用优先），无定时任务（`TenantUseCase.isDerivedExpired`）。default 禁停；停用级联后代 + afterCommit 撤会话（失败 WARN 不回滚）。
- 配额三级链（`TenantQuotaUseCase.resolveQuota`）：租户覆盖 sys_tenant_quota → 套餐 sys_tenant_package.quota_storage_bytes → 平台缺省 StorageProperties（1GiB，0=不限）；NULL=继承，0=不限；一期仅存储项。

## 2. 租户隔离
- 链路：登录 tenantCode 查目录（fail-closed）→ JWT claim `tenantId`（UserTokenIssuer）→ 网关 StripSpoofedHeadersFilter(100) 剥伪造身份头 → AuthFilter(200) 验 JWT → RevocationCheckFilter(250) → InjectIdentityFilter(400) 注内部 JWS+身份头 → 服务侧 InternalTokenAuthFilter 绑 `TenantContext`（ThreadLocal，common-context）finally 清理。单体形态 BootstrapAuthFilter 复刻同语义。
- 数据层：MyBatis-Flex `@Column(tenantId=true)`（20+ 实体）自动加 `tenant_id=?`（SELECT/UPDATE/DELETE 全覆盖，insert 自动覆盖租户列）；`FlexTenantFactory` 唯一取值源 TenantContext；未绑定 fail-closed（读恒空集哨兵 `__acme_tenant_unbound__`）；`TenantWriteGuard` 未绑定写入抛 TENANT_REQUIRED。行为佐证 `TenantFilterFlexTest`。
- 跨租户唯一入口：`@IgnoreTenant("access-point")` + IgnoreTenantAspect（AOP）+ TenantBypassScope ThreadLocal 深度计数 + 强制 CrossTenantAuditLogger 留痕（审计失败 fail-closed）。
- 异步无自动透传，全部显式：ContextSnapshot capture/restore；MQ 发送 ContextHeaders.outbound、消费 ConsumeContexts（ContextScope 关闭清理）；workflow 命令载荷 bind/clear；Feign InternalTokenRequestInterceptor 签内部 JWS 下游还原。WS 握手只验 userId（不绑 TenantContext），推送隔离靠 DB 租户过滤。
- 会话：auth_session.tenant_id（存租户代码、不标 @TenantId，按 tokenId 全局定位）；停用租户经 system→auth internal 契约批量吊销（DB 置 KICKED + Redis auth:revoke:<tokenId>，网关每请求检查，FAIL_OPEN）。
- 非 DB：framework-cache 无内建租户键（约定）；sys_config 每租户一行；文件 objectKey 带 `files/{tenantCode}/` 段；调度域 SchJobDefinition 有意不做自动过滤（平台控制面）。

## 3. 创建租户
- 运行时入口（admin，权限 `system:tenant:create`）：
  - `POST /admin/v1/system/tenants` → TenantUseCase.create：仅写 sys_tenant 一行（status=ENABLED）。
  - `POST /admin/v1/system/tenants/init` → TenantInitUseCase.initialize：开通一个事务写 6 表——sys_tenant、sys_role（tenant_admin / 模板 rolePresets）、sys_role_permission（启用且非保留域权限；保留前缀 system:tenant:/infra:audit:/system:tenant-package:/system:tenant-template:）、sys_role_menu（含祖先目录补全）、sys_user（初始管理员，无凭据）、sys_user_role；模板路径另写 sys_config configEntries 并回填 package_id（快照语义）。方法内 bind 新租户 TenantContext，写入自动落新租户。
- 后置两步（可选）：`PUT /admin/v1/auth/credentials/users/{adminUserId}/password`（设初始密码，auth:credential:manage）；`PUT /admin/v1/system/tenants/{tenantId}/quota`（配额覆盖，不设走套餐/平台缺省）。
- default 租户唯一来源＝Flyway 种子 `platform-system-service/src/main/resources/db/migration/system/V2__seed_baseline.sql`（id=1,'default'，ON CONFLICT 幂等）；同文件种 super_admin/admin。internal 端点只读、无创建通道。
- 前端：`web/src/pages/TenantsPage.tsx`（路由 /tenants，权限 system:tenant:list），新建按是否选开通模板分流 tenantInit/tenantCreate；API 封装 packages/core/src/api/system.ts。
- 手工 SQL 仅应急补目录行（雪花 id、审计列、deleted=false 对齐 V2 口径），不推荐（易漏六表初始化、绕过审计）。

## 4. 开通模板定义位置（补充核查 2026-11-24）
- **无代码/种子预定义**：迁移中无 `INSERT INTO sys_tenant_init_template`；模板全部为运行时经管理端 CRUD 创建，落库 `sys_tenant_init_template`（V32，content jsonb 快照）。
- 入口：AdminTenantInitTemplateController `/admin/v1/system/tenant-templates`（GET 分页/POST/PUT/{id}/DELETE/{id}，权限 system:tenant-template:list/create/update/delete，审计 system.tenantTemplate.*）；用例 `TenantInitTemplateUseCase`（校验+落库，code uk，version 乐观锁）。
- content 形状：`{adminUsernamePrefix?, adminNickname?, rolePresets:[{roleKey,roleName,permissionCodes[]}], configEntries:[{configKey,value}]}` + 模板级 `default_package_id`。校验：空角色集拒、保留前缀权限码拒（复用 TenantInitUseCase.RESERVED_PERMISSION_PREFIXES）、重复 roleKey 拒；configKey 已知六键（watermarkEnabled/captchaEnabled/lockIdleTimeoutMs/footerCopyright/footerVisible/watermarkTemplate），未知键开通时 WARN 跳过。
- 消费：开通 `TenantInitUseCase.initialize` 按 templateCode 读模板，**快照语义**——改/删模板不影响已开通租户。
- 前端：`/tenants` 页开通模板管理 Tab；API 封装 packages/core/src/api/system.ts `tenantTemplates`；dev 演示数据仅存在 msw mock（web/src/mocks/data.ts:189），非后端事实源。

## 5. 平台管理面的跨租户实现（补充核查 2026-11-24）
- 目录数据天然全局：sys_tenant/套餐/模板/菜单/权限点无 tenant_id 列，Flex 不过滤；管理读与写全部收口 admin 端点 + @RequirePermission（保留域权限点，模板不可授，仅内置 super_admin 持有）。
- 归属数据跨租户读/写走 @IgnoreTenant 显式旁路 + CrossTenantAuditLogger 强制留痕（fail-closed）：TenantUseCase page("tenant-list-lookup")/get("tenant-detail-lookup")/findByCode("tenant-code-lookup")/tree("tenant-tree-lookup")/listEnabled("login-tenant-directory")；TenantQuotaUseCase resolveQuota("tenant-quota-resolution")/usage("tenant-usage-aggregation")/resolveQuotaBytesByTenantId（列表/树批量富化免 N+1）。
- 管理写：updateStatus 停用级联后代 + afterCommit 经 system→auth internal 契约逐租户撤会话；setTenantQuota 覆盖行写面。
- 超管管单个租户内部：TenantSwitchUseCase——前置经 system 内部鉴权面实查内置角色行（roles 仅查询键，fail-closed），签发目标租户 token（roles claim=super_admin）；**内置角色跨租户回退**（decisionService.builtInRoleIdsOf 按码取跨租户行、MenuUseCase.visibleMenuIdsOfBuiltIn 旁路）使 super_admin 在目标租户全权，非内置角色码一律丢弃防提权（MeUseCase 同口径）。
- 服务间/单体形态：acme-bootstrap Local* 客户端以 platform-system 服务身份直调用例，无网关上下文，跨租户口径由用例 @IgnoreTenant 声明。

## 6. 对照主流多租户设计的缺陷分析（2026-11-24）
P0（结构性，可能越权/串数据）：
1. 无 DB 层兑底：隔离全靠 ORM 谓词，PG 迁移无 RLS；原生 SQL/直连/新数据源均在防线外（usage/findByTenantCode 已是手写条件例外通道）。主流：shared-schema 叠加 RLS 双层防御。
2. 缓存无租户维度：framework-cache 无租户键，全仓无 @Cacheable——启用注解式缓存即串租户；主流：cache key 强制含租户段。
3. 异步/MQ 上下文靠纪律：ThreadLocal 无自动传递全靠手工 bind/clear；MQ 消费端信任约定头无来源校验（ConsumeChannelInterceptor 已核无校验）。主流：容器级 context-propagation/TTL + 消息签名。
P1（安全暴露面）：
4. 登录页公开枚举全部租户：GET /admin/v1/auth/tenants（auth:false，core/api/auth.ts:134）返回全部启用租户 code+name；captcha-policy/public-config 按 tenantCode 可探测。主流不公开租户目录。
5. 会话撤销 FAIL_OPEN：Redis 撤销检查异常放行，停用租户会话残留至多 access TTL 30min；主流高敏撤销 fail-closed 或短 access+refresh。
6. 跨租户旁路审计只进应用日志（CrossTenantAuditLogger 不落库），不可检索/告警；主流审计持久化可查。
P2（规模化与产品完备度）：
7. 限流键无租户维度（RateLimitKeyInput 只有 channel/account/clientIp），无噪声邻居防护；配额仅存储。主流 per-tenant rate limit / pooled-silo 分层。
8. 能力开关 TENANT-2 未落地（套餐仅菜单+存储配额）。
9. 无租户退出/删除/导出（offboarding）：仅停用+软删。
10. tenant_id 用业务码字符串作 20+ 表过滤列（索引宽、改名全库迁）；username 全局唯一限租户自主性。
11. 层级仅生命周期级联，子租户可见性/配额归集未定义（docs §6.3 悬而未决）。
12. 《架构设计》《Modules.md》缺失；无隔离渗透测试。
公允面：fail-closed 默认、写守卫、旁路强制审计、剥伪造头+内部 JWS、切换实查内置角色、隔离有集成测试回归——起点扎实；P0/P1 建议列入改进。
参考：AWS SaaS Tenant Isolation Strategies（silo/pool/bridge）、Azure storage-data multitenant guidance（RLS）、Azure Noisy Neighbor antipattern。

## 已知缺口
- 代码注释引用的《架构设计》7.2/12.4.1 与《Modules.md》在仓库内不存在，权威分层编号（FR-00x/Q-D04）只能从代码与 REQ 反推。
- TENANT-2「服务能力开关」为规划未完成项（docs/platform-enhancement-plan-2026-11-21.md §6；SysTenantPackageCapability 消费面未见）。
- framework-cache 业务层是否手动拼租户缓存键未逐模块核查（已确证框架层无内建租户维度）。
