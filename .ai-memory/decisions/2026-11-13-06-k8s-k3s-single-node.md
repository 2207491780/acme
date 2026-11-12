# 部署编排改用 k8s：k3s 单机（4C4G），取代 compose 方案

> 取代 `2026-11-13-05-cloud-deploy-phase1-docker.md` 中的编排选型与「单机多环境常驻」部分；
> 其「分两期、一期先落运行环境、镜像仓库用 ACR、CI 平台延后」的结论继续有效。

## 背景

一期方案最初定为 Docker Compose 单机多环境。用户明确部署目标改为 Kubernetes；服务器为已有
国内单机，规格 **4核4G**，要求承载环境 + 前后端全栈。4G 内存是硬约束：k3s 本体约 0.5–1G，
OS 约 0.3G，留给 Pod 的预算约 2.5–3G，而 ACME 全栈（PG/Redis/Nacos/Java 网关与业务服务/web）
原始需求即超 3G，必须按限额配平。

## 决定

- **编排**：Kubernetes，发行版选 **k3s**（CNCF 认证、内置 containerd / local-path 存储 /
  Traefik Ingress），单节点装在现有服务器上；不用 kubeadm、不接云托管。
- **清单**：Kustomize `base` + `overlays/{dev,test,prod}`（替代原 compose 三文件），namespace
  按环境隔离（`acme-dev` 等）。
- **资源策略**：4G 上**同一时间只常驻一个环境**（默认 dev）；全部 Deployment 设内存 limit，
  Java 服务用 `-XX:MaxRAMPercentage` 适配容器限额，Nacos 降配 standalone，PG/Redis 调小缓冲。
- **构建位置**：镜像在开发机或后续 CI 构建，推 ACR 后服务器只 pull——4G 服务器不做 Maven/pnpm
  构建，必要时用 swap 应对偶发峰值，不作为常态。
- **脚本契约不变**：`deploy.sh` 内部实现改为 `kubectl apply -k` + rollout 检查，
  `healthcheck.sh` 改为 k8s 原生 probe + 端点探活；`cd.yml` 调用方式零改动。
- 有状态服务（PG/Redis/Nacos）用 StatefulSet + local-path PV，演示规模不做主从/高可用。

## 被否的选项

- **维持 Docker Compose**：用户定向 k8s；且 k8s 原生 probe/滚动更新/namespace 隔离对后续
  CI/CD 接线（rollout 状态即门禁信号）更友好。
- **kubeadm 标准 k8s**：控制面组件内存开销远超 k3s，4G 单机不可行。
- **云托管 ACK/TKE**：master 托管月费在演示项目上不划算，且已有单机即可承载 k3s。
- **多环境同机常驻**：4G 放不下两套全栈；test/prod 由 overlay 支持，按需部署起停。
- **服务器上构建镜像**：Maven/pnpm 构建内存峰值在 4G + 全栈常驻下必然挤压运行时，构建与
  运行环境分离。
