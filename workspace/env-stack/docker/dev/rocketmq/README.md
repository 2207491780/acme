# rocketmq — 消息中间件（nameserver + broker）

- 用途：poc-gate POC-3「Stream 多 binder 路由」验证前置（FR-003 前置条件、Q-003 已授权）；**仅开发用**，不进入正式交付、不建生产编排（Backend.md 第 15 章为生产口径）
- 镜像：`apache/rocketmq`（版本由 `ROCKETMQ_VERSION` 控制，默认 5.3.1，与 Spring Cloud Alibaba BOM 线的 rocketmq-client 5.3.x 对齐；钉版本不用 `latest`，M-13 精神）
- 端口（仅 127.0.0.1）：
  - `${ROCKETMQ_NAMESRV_PORT}`（9876）—— nameserver，客户端注册发现入口
  - `${ROCKETMQ_BROKER_PORT}`（10911）—— broker remoting，客户端收发主端口
  - `${ROCKETMQ_BROKER_FAST_PORT}`（10909）—— broker fast remoting（VIP 通道）
- 拓扑：`rocketmq-nameserver` + `rocketmq-broker` 两容器，同网络 `acme-dev-stack`；数据/日志用命名卷（`acme-rocketmq-*`）
- 运行用户：`user: root`——命名卷由 docker 创建属主为 root，与镜像默认 uid 3000(rocketmq) 不匹配会导致 broker 初始化失败（写 store/logs 权限拒绝）；仅开发编排规避，非生产口径
- 关键配置（[broker.conf](broker.conf)）：`brokerIP1 = 127.0.0.1`（宿主机侧应用经映射端口连接时，broker 注册到 nameserver 的地址须宿主机可达）；`autoCreateTopicEnable = true`（开发期自动建主题；正式主题治理见 Backend.md 13.2.4）
- 健康检查：nameserver 经 `mqadmin clusterList` 判定；broker 经 `mqadmin clusterList` 输出含 `broker-a` 判定

```bash
# 启动 / 查看（仓库根执行；两容器一并拉起）
docker compose -f env-stack/docker/dev/compose.dev.yml up -d rocketmq-nameserver rocketmq-broker
docker compose -f env-stack/docker/dev/compose.dev.yml ps
docker compose -f env-stack/docker/dev/compose.dev.yml logs -f rocketmq-broker
```

## 连接示例

```text
nameserver 地址：127.0.0.1:${ROCKETMQ_NAMESRV_PORT}（默认 127.0.0.1:9876）
broker 地址：由 nameserver 返回（brokerIP1=127.0.0.1，端口 ${ROCKETMQ_BROKER_PORT}）
```

验证主题命名 `poc-gate-*`（架构设计 4.3：仅开发环境存在，不入正式主题清单）。

## 中间件侧观测（POC-3 证据采集）

```bash
# 查看 broker 节点注册
docker exec acme-rocketmq-nameserver sh mqadmin clusterList -n localhost:9876
# 查询主题消息（POC-3 发送路由/隔离观测）
docker exec acme-rocketmq-broker sh mqadmin queryMsgByTopic -n localhost:9876 -t <主题名>
```
