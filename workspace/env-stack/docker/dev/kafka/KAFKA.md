# kafka — 消息中间件（KRaft 单节点）

- 镜像：`apache/kafka`（版本由 `KAFKA_VERSION` 控制，默认 4.0.0）；端口 `${KAFKA_PORT}`（默认 9092，仅 127.0.0.1）
- 监听器：
  - `PLAINTEXT_HOST://127.0.0.1:9092` —— 宿主机侧应用（IDE 启动）连接地址 `127.0.0.1:9092`
  - `PLAINTEXT://kafka:29092` —— dev-stack 网络内其他容器连接地址 `kafka:29092`
- 开发期允许自动建主题；正式主题治理按 Backend.md 13.2.4（按业务域设主题、消费组与服务名对应）
- 消息约束：消息携带 traceId、幂等键、版本号，主题配死信（13.2.2）；消费必须幂等（M-12）

```bash
docker compose -f env-stack/docker/dev/compose.dev.yml exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
# 建主题示例：
#   .../kafka-topics.sh --bootstrap-server 127.0.0.1:9092 --create --topic demo.events --partitions 3 --replication-factor 1
```
