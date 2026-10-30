# redis — Redis

- 镜像：`redis:7.4-alpine`；端口 `${REDIS_PORT}`（默认 6379，仅 127.0.0.1）
- 密码：`env-stack/docker/dev/.env` 的 `REDIS_PASSWORD`，经 compose 命令注入，不写入 [redis.conf](redis.conf)
- 用途：缓存（framework-cache L2）、分布式锁（framework-lock）、限流令牌桶、ShedLock Redis 模式
- 缓存键带服务前缀（Backend.md 12.3.2）

```bash
docker compose -f env-stack/docker/dev/compose.dev.yml exec redis redis-cli -a "$REDIS_PASSWORD" ping
```

连接给宿主机侧应用：`redis://127.0.0.1:${REDIS_PORT}`，密码取 `REDIS_PASSWORD`。
