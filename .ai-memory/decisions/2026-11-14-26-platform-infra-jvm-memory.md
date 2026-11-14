# platform-infra JVM 内存定容：512Mi 限额 + 服务级 JAVA_TOOL_OPTIONS 覆盖

> 修订 ADR 2026-11-13-06 的资源策略在 platform-infra 上的具体取值；其 4G 单机单环境原则不变。

## 背景

dev-ff213db 部署 acme-dev 时 `platform-infra` 滚动超 progress deadline（CI 红灯，2026-11-14）。
集群实测：PVC `platform-infra-storage` 已 Bound（local-path 正常）；新 RS pod 启动约 5.5 分钟后
被杀（exit 137，startupProbe 60×5s 走满），31 次重启循环，日志反复 `java.lang.OutOfMemoryError:
Metaspace`。根因：3bef39b 为 platform-infra 接入 framework-storage / framework-excel（fastexcel）/
framework-job（ShedLock），启动期类加载量上升，突破共享 ConfigMap `JAVA_TOOL_OPTIONS` 的
`-XX:MaxMetaspaceSize=96m` 上限——JVM 在 Spring 启动中途即抛 OOM，永远到不了 ready。旧镜像
dev-2651c65（无该批依赖）在 384Mi 限额下正常，且滚动更新 maxUnavailable=0 保住旧 pod，服务未中断。
共享 JVM 参数不可全局上调：gateway/auth/system 同一限额 384Mi、heap 占 70%，若元空间上限放到 192m，
heap+metaspace 理论峰值（269+192）将越过 384Mi 容器限额；4G 单机也不允许普遍加限额（ADR 2026-11-13-06）。

## 决定

- **base/platform-infra.yaml 容器 env 覆盖**（k8s 规则 `env` 优先于 `envFrom`）：
  `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -XX:MaxMetaspaceSize=192m"`。
- **内存 limit 384Mi → 512Mi**：heap 上限 205Mi + metaspace 192Mi + JVM 开销约 50Mi ≈ 447Mi，
  留约 65Mi 余量；node 总限额升至约 2.75G，仍在 4G 单机 Pod 预算（约 2.5–3G）内。
- 其他 Java 服务维持共享参数不变（96m 对其够用且限额贴线，全局上调会破坏各自 heap 余量）。
- 实际元空间需求未测到精确值（JRE 镜像无 jcmd），192m 按「实测突破 96m + 依赖增量估计」取余量；
  后续若仍贴线优先下调 MaxRAMPercentage 而非继续加容器限额。
- 部署侧铁律不变：删除 PVC 前先缩容 platform-infra（local-path 回收即删数据，
  见 dev/test overlay platform-infra-storage.yaml 注释）。

## 被否的选项

- **全局上调 MaxMetaspaceSize（共享 ConfigMap 96m→192m）**：gateway/auth/system 的
  heap 70% × 384Mi 与 192m 元空间叠加将越过容器限额，且 4G 单机预算不允许普遍提限额。
- **维持 384Mi 只改 JVM 参数**：heap 40% × 384 = 153Mi + 192m 元空间 + 开销已贴 384Mi，
  无余量，仍有 cgroup OOMKill（exit 137）风险。
- **去掉 MaxMetaspaceSize 上限**：元空间无界增长时故障形态退化为 cgroup OOMKill，
  失去「OOM Metaspace」这一可归因信号。
- **回滚镜像到 dev-2651c65**：只掩盖问题，3bef39b 的功能（文件服务/Excel/任务）必须上线。
