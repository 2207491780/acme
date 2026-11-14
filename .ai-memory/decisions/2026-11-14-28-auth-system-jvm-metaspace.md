# platform-auth / platform-system 按 ADR 26 范式定容：512Mi + 服务级 JVM 覆盖

> 扩展 ADR 26（platform-infra 定容）：其「其他 Java 服务维持共享参数不变」的判断被实测推翻——
> 96m 共享元空间上限对已增长类量的服务不再安全；4G 单机单环境原则仍不变。

## 背景

dev-3e2ca36 上线后公网验证码（`GET /admin/v1/auth/captcha`）报 500 `SYS-00-0006`。三路对照
（CF 隧道 / Traefik / 网关直连）响应一致，排除接入层；platform-auth 日志实锤
`Handler dispatch failed: java.lang.OutOfMemoryError: Metaspace`，pod 曾因此重启。

- 验证码功能 `29fe6dd`（Hutool LineCaptcha）**在旧镜像 dev-2651c65 内已存在**且正常——
  AWT 绘图类为懒加载，首次访问才进元空间。旧镜像 96m 够用；新镜像 auth 经 b6edd1d
  （刷新轮换/密钥治理）类量增长后，验证码懒加载尖峰击穿 96m 上限。
- 同批实测 **platform-system metaspace 用量 81Mi（共享上限 96m 的 85%）**——5cc5d9c
  权限判定改查库后贴线，随时复现同类 OOM。
- gateway 无 actuator 不可测，代码无类量级增长，维持共享参数并观察。

## 决定

- platform-auth / platform-system 均套用 ADR 26 已验证范式：
  `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -XX:MaxMetaspaceSize=192m"`（服务级 env 覆盖
  envFrom）+ 内存 limit 384Mi/320Mi → **512Mi**。
- 节点 limit 总量升至约 3.07G，仍在 4G 单机 Pod 预算（约 2.5–3G，限额非实占）。
- 后续新增 Java 服务或框架级依赖增长时，先按本范式评估 metaspace 预算再上线；
  部署后可用 `/actuator/metrics/jvm.memory.*` 复测（gateway 除外）。

## 被否的选项

- **只修 auth 不动 system**：85% 贴线是实测事实，单独再走一轮 3 小时构建只为修同一类问题不划算。
- **全局上调共享 MaxMetaspaceSize**：同 ADR 26 结论，gateway/auth/system heap 70%×384Mi 与
  更高元空间上限叠加将越过各自限额。
- **回滚镜像**：验证码类量增长来自正式功能（刷新轮换修复、权限判定修复），不可回退。
