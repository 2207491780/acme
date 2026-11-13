# 恢复并加固本地↔云端调试隧道，补全 cloud-dev 运行环境定义

> 承接 `2026-11-13-11-env-file-pointer.md`（environment.file 指向 cloud-dev.yaml）；
> 隧道方案原实现见已回退提交 e7e33b8（随真域名 ingress 一并回退，非方案本身问题）。

## 背景

`environment.file: cloud-dev.yaml` 声明了当前运行环境，但本地→云端中间件的连接通道缺失
（原 dev-tunnel.sh 于 2651c65 随域名改动一并回退），cloud-dev.yaml 的 MCP 与 notes 均为占位。
用户要求（2026-11-13）：完成 cloud-dev 的配置。

## 决定

1. **恢复 dev-tunnel.sh**（SSH + kubectl port-forward，回环映射 15432/16379/18848/19848/19888，
   服务器侧 27xxx 仅绑 127.0.0.1），保留原实测链路，两处加固：
   - 去掉硬编码服务器 IP 缺省值：`ACME_SRV_HOST` 必填（`${VAR:?}`），避免误连与地址入库；
   - prod 由文档约定升级为**代码级拦截**（exit 1），并校验 env ∈ dev|test。
2. **cloud-dev.yaml 补全**：登记 dev-db MCP（stdio postgres server，DATABASE_URL 经隧道端口 15432、
   密码引用 `${PG_PASSWORD}`——由隧道脚本 export 行注入）；notes 写明通道用法、Nacos 同名注册注意事项。
3. **RUNBOOK §8.5 恢复**：前置变量、前后端联调命令、prod 禁用。
4. **凭证纪律**：脚本打印云端凭证 export 行，明确由用户终端承接，**不得经 AI 工具运行或转录**（安全红线）；
   因此本次仅做静态验证（sh -n + 三道拦截），端到端连通由用户在本机终端执行验证。

## 被否的选项

- **保留原脚本硬编码 IP 缺省**：否决——服务器地址不应入库（RUNBOOK 一律占位），必填环境变量更稳。
- **kubectl port-forward 直连（不经 SSH）**：否决——kubeconfig 仅服务器侧持有，开放 API Server 公网暴露面更大。
- **NodePort 暴露中间件**：否决——安全组只放行 22/80/443 的纪律不变，中间件不得公网暴露。
- **cloud-dev.yaml 增加结构化 connection 段**：否决——端口映射已在脚本与 RUNBOOK 单一维护，yaml 再存一份会漂移；沿用 ENV-GUIDE 既有 schema。