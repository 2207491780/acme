# init-admin — Nacos 3.x 本地管理员初始化

Nacos 3.x **不创建默认用户**，而控制台/管理 API（8080 与 `/nacos/v3/admin/*`）要求登录。
首次建栈或 `down -v` 清卷后，需向内置 derby 写入本地管理员（口令见 `env-stack/docker/dev/.env` 的 `NACOS_USERNAME`/`NACOS_PASSWORD`）。

```bash
# 仓库根执行；SQL 与工具类在本目录（DerbyExec.java / init-admin.sql）
docker stop acme-nacos

# 从镜像抽出内置 derby 引擎 jar（用完可删，中间产物归 .ai-tmp/）
docker cp acme-nacos:/home/nacos/target/nacos-server.jar .ai-tmp/nacos-server.jar
unzip -o -j .ai-tmp/nacos-server.jar "BOOT-INF/lib/derby-10.14.2.0.jar" -d env-stack/docker/dev/nacos/init-admin/
javac --release 17 env-stack/docker/dev/nacos/init-admin/DerbyExec.java

# 对数据卷里的 derby 执行 init-admin.sql（一行一条语句）
docker run --rm --entrypoint sh \
  -v acme-nacos-data:/home/nacos/data \
  -v "$PWD/env-stack/docker/dev/nacos/init-admin:/work" \
  nacos/nacos-server:v3.0.0 \
  -c "java -cp /work:/work/derby-10.14.2.0.jar DerbyExec /work/init-admin.sql"

docker start acme-nacos
# 验证登录（返回 accessToken 即成功；口令取 .env 的 NACOS_PASSWORD）
curl -s -X POST "http://127.0.0.1:8080/v1/auth/login" -d "username=nacos&password=<NACOS_PASSWORD>"
```

- 表在 **NACOS schema** 下（`NACOS.USERS`/`NACOS.ROLES`），SQL 已限定；未限定名会报表不存在。
- 若插入时报表不存在：先 `docker start acme-nacos` 让它建好 schema，再 `docker stop` 后重跑插入。
- 改口令后同步重算 `init-admin.sql` 中的 bcrypt 哈希（生成命令见该文件注释）。
- `DerbyExec.java` 为本地运维工具，不参与业务构建；编译产物不入库。
