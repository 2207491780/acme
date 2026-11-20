# 2026-11-20-01 — platform-system 迁移重编号 V21→V23（Flyway 乱序校验致部署失败）

## 背景

prod-233a7be 发布时 platform-system Pod CrashLoopBackOff，部署链路中断（探活/隧道同步未执行）。
根因：并行分支迁移段位错序——`V22`（六页菜单，90a61be）先合入**并已部署上线**（prod 库
`platform_system.flyway_schema_history` 已应用至 V22），`V21`（个人中心，bd39c07）后合入。
新 jar 启动时 Flyway 校验「Detected resolved migration not applied to database: 21」拒绝乱序执行，
服务启动即崩（exit 1，6 秒退出）。云端 acme_dev 库不存在（本地开发走本机 .dev-stack），仅 prod 受影响；
旧版 Pod（prod-90a61be）全程在跑，服务未中断。

## 决定

重编号 `V21__profile_preference_push.sql` → `V23__profile_preference_push.sql`（内容零改动，仅头注释
标注重编号经纬），同步修正 `test/resources/schema.sql` 注释与 application.yml 过时注释（V19→V23），并在
application.yml 增部署纪律注释：**并行分支的低段位迁移不得晚于高段位上线**。沿仓库先例 3209c36
（V19 重编号——V18 已被占用）口径。

本地 dev 库注意事项（未在本次处理）：若本机 PG 已应用过 V21，下次本地启动将报
「applied migration not resolved」——用既有 .ai-tmp/PgReset.java 流程重置本地库即可（V21/V23 为同内容
个人中心 DDL，重置后 V23 全新应用）。

## 被否的选项

- **flyway.out-of-order=true**：一行配置即恢复部署，且以后并行分支错序可自动兼容。否决：用户选择维持
  迁移严格顺序的仓库先例；放宽顺序保证属于策略变更，且会掩盖「高段位先上线」的流程缺陷而非纠正它。
  复发风险已改由 application.yml 部署纪律注释承接（低段位未上线前，禁止携带更高段位迁移的版本先部署）。
- **手工向 prod 库补写 V21 历史行/手工执行 DDL**：绕过 Flyway 版本化机制，库状态与 jar 失配且不可审计，
  违背「DDL 经 Flyway 单次执行」口径，否决。
