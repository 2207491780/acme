# 全面审阅修复——遗留项清单（2026-11-14）

随 fix/review-findings 修复任务登记：P0 全修、P1 全修、P2 修快赢项；以下为**有意延迟**项（含原因），
按区域分组。修复时逐条勾销；推翻本清单口径需在正文标注。

## 后端（需部署侧/产品决策或独立任务）

1. **prod Nacos 鉴权未启用**（base manifest `NACOS_AUTH_ENABLE=false` 对全部环境生效，prod overlay 未覆盖）：
   开启需同步改造 `seed-nacos.sh`（OpenAPI 带 token）与 dev/test 联调脚本，且无法本地验证，属部署侧专项。
2. 登录接口区分 ACCOUNT_LOCKED/ACCOUNT_DISABLED（账号枚举面）：注释自辩与 FR-005 不冲突，收口需产品口径。
3. 验证码一次性消费 GET→比较→DELETE 非原子：改 Lua（可复用 RedisIdempotentRecordStore 先例）+ 测试，独立小任务。
4. 网关 CORS 缺省 `allowedOrigins:*` 语义反转（缺省拒绝、开发显式放通）：影响本地联调 DX，随 dev-stack 文档一起做。
5. 网关接入 Nacos 配置中心（与三 platform 服务对齐，P2-10）：需 seed-nacos.sh 增 `acme-gateway` data-id。
6. `AuthSession.rotate` 直接改主键属性（旧 jti 审计链断裂，设计上接受）：如需保留历史另立迁移。
7. `SqlMasking` 正则不匹配反引号标识符（慢 SQL 参数日志基本失效）、`AuditAspect` 用 StandardEvaluationContext、
   Lombok+MapStruct 全仓库零使用（空转依赖）、acme-cloud dependencyManagement 死配置 business-content、
   两处 Flyway 注释仍写 framework-jpa——均为低风险清理，随下次触碰对应模块时顺手处理。
8. 登录 BCrypt 常量时间 / `Long.parseLong(userId)` 500：随登录链路下次迭代。
9. `V5__auth_session_refresh_token_index` 已加索引（本次已修），既有部署需重跑 Flyway 生效。

## 前端

1. **引入 ESLint + react-hooks 插件**（P0-2 的根因是门禁盲区）：依赖选择与规则基线属工程决策，建议独立任务；
   完成后补 App 主链路最小 render 测试。
2. token 存储升级（localStorage 明文 → 更安全方案）与「记住我」默认值：产品决策。
3. void 接口 204/空体兜底（`client.ts` EMPTY_BODY 误判）；desktop dev 导航 `startsWith` 精确化；
   `destroyOnClose`→`destroyOnHidden` 批量替换；表单校验拒绝 unhandled rejection；
   角色目录加载失败静默清空（UsersPage/RolesPage catch→空数组）——均为 P3 机械项，随页面迭代顺手处理。

## .ai 配置体系

> 2026-11-14 全面审查（台账见 `ai-coding-audit-2026-11-14.md`，决策见 ADR 2026-11-14-21/22/23/24）已解决 1、2、5：

1. ~~根部入口孤儿检测与 scopes 覆盖警示~~ ✅ 已解决：孤儿扫描扩展到根部（非递归）与 scopes 同级目录（新增 2 用例，顺带修复 inspectPath 根路径返回 null）；SCOPED-RULES.md 已补「路径即仓库路径」覆盖警示。
2. ~~commit-msg 不校验 `Assisted-by` trailer~~ ✅ 已收口：规则注明「暂无机器校验，PR 描述审查把关」（git-workflow.md）。
3. pre-push 零值 SHA 硬编码 40 位（SHA-256 仓库下删除检测失效）：仓库切 sha256 时一并处理。**仍遗留**。
4. mirror-sync 作业对「对端分支不存在」报 git diff 128 笼统失败：首次接线期提示优化。**仍遗留**。
5. ~~parallel-dev.md 与 ADR 03 的暂存清单口径不一致~~ ✅ 已解决：pre-commit 清单补 `.ai-memory` 与镜像/CI 三层统一（ADR-22 以新记录修正 ADR-03 表述）。

## ui-prototype（归档区）

1. core/tests 6 个文件不可运行（依赖缺失的 `../src` 聚合出口）+ `frontend-wiring.patch` 已过期：
   恢复路径需按 UI-PROTOTYPE.md 映射表重建（补 index 导出 + 按意图重打 patch），属「恢复运行」专项。
2. 7 个孤儿屏（Announcement/FeatureFlags/OrgManage/Backup/AdvancedTable/Onboarding/Login）未注册进 ProtoNavigator；
   web 48 vs mobile 40 注册表漂移——补注册或明确删除，随下次原型同步做。
3. CommandPalette「Esc 关闭」提示与行为不符、SearchCenter 可点击行键盘可达性、CommandPalette/Pipeline 顶部
   Math.random 验证码非确定性——P3 机械项。

## 部署/CI

1. test overlay 缺 ingress-host 覆盖（与 dev/prod 不一致，探活仍可用）。
2. cd.yml：scp 同步不清理服务器残留文件（改 rsync --delete 需服务器确认）、ssh-keyscan TOFU（known_hosts 预登记替代）。
3. ci.yml 补 Maven/pnpm 缓存（加速）；ai-rules-sync.yml 与 ci.yml ai-config 作业功能重复（可裁撤其一）。
4. `.worktrees/feat-view-prototype` 目录名与检出分支（feat/login-prototype）脱钩：`node .ai/worktree.mjs remove` 按
   目录名反查会失败，收尾时用 `git worktree remove` 处理（操作项，非代码缺陷）。
