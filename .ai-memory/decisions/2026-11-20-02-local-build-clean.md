# 2026-11-20-02 — local-build.sh 打包加 clean（src 资源改名后 target 残留入 jar）

## 背景

承接 2026-11-20-01：迁移重编号 V21→V23 后重新构建部署，platform-system 仍报
「Detected resolved migration not applied to database: 21」。取 Pod 日志与本地
`target/classes` 比对证实：`mvn package` 不清理 target，改名前的
`V21__profile_preference_push.sql` 残留在 `target/classes/db/migration/system/`，
与 V23 并存打进 jar，Flyway 双解析后 V21 依旧触发乱序校验。第一次崩溃（prod-233a7be）
是库侧乱序，第二次（prod-83729a3）是构建产物污染，两层问题先后显形。

## 决定

`local-build.sh` 服务打包命令由 `-am package` 改为 `-am clean package`，脚本内注释标注事故经纬。
后续所有本地构建保证产物与 src 严格一致。

## 被否的选项

- **仅手工删 target 残留、不改脚本**：本次能过，但下次任何资源改名/删除都会复发（测试资源、
  i18n 文案、配置文件同理），属流水线级隐患，否决。
- **改用 maven-resources-plugin/配置 incremental**：引入插件配置复杂度，`clean package` 语义
  直接且构建耗时增量可接受（全量 clean 后四模块打包实测增量分钟级），否决。
