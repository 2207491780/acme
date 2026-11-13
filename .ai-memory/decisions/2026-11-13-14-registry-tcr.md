# 镜像仓库事实源为腾讯云 TCR（取代 05/06 的 ACR 结论）

> 取代 `.ai-memory/decisions/2026-11-13-05-cloud-deploy-phase1-docker.md` 与
> `2026-11-13-06-k8s-k3s-single-node.md` 中「镜像仓库用阿里云 ACR」的结论；
> 两记录其余结论（分两期、一期先落运行环境、CI 平台延后、4G 服务器不做构建等）继续有效。

## 背景

05/06 决策选定阿里云 ACR（国内拉取快且稳）。实际落地使用腾讯云 TCR，用户确认
（2026-11-13）：「实际用的是腾讯云 TCR」。文档与配置的 ACR 残留口径已统一修正
（提交 efbfecb：RUNBOOK §3、ENV-STACK、registry.example.env、kustomization、deploy.sh）。
一致性审核（2026-11-13）发现：口径已改但决策链未标注取代，05/06 仍载 ACR 为有效结论。

## 决定

1. **镜像仓库 = 腾讯云 TCR 个人版**（`ccr.ccs.tencentyun.com/acme-ns`），五个私有仓库
   （gateway / platform-auth / platform-infra / platform-system / web）；CI 构建推 TCR、服务器只 pull 的链路不变。
2. **本记录补齐取代标注**：决策只增不改，推翻旧结论必须新增记录并在开头标注取代——
   本次审核暴露的治理缺口（改事实不改决策链）以此闭环。

## 被否的选项

- **只改文档不补 ADR**：否决——决策链与事实漂移，后续会话读 05/06 会拿到失效结论；审核已实证该风险。
- **回改 05/06 原文**：否决——违反「只增不改」纪律。