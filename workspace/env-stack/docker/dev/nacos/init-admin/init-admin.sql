-- Nacos 3.x 本地管理员初始化（镜像不建默认用户；数据卷重建后执行，步骤见 README.md）
-- 口令哈希对应同目录上级 .env 的 NACOS_PASSWORD（不入库，勿在此写明文）；
-- 本哈希是本地开发口令的 bcrypt 值，仅本地有效；
-- 若改口令，用新口令重算 bcrypt（$2a$）后替换下行哈希：
--   uv run --python 3.13 --with bcrypt python -c "import bcrypt; print(bcrypt.hashpw(b'<新口令>', bcrypt.gensalt(rounds=10, prefix=b'2a')).decode())"
INSERT INTO NACOS.USERS (username, password, enabled) VALUES ('nacos', '$2a$10$woHn3FjSOM9pWyxLb9vz4.TdI7p3.ip2gh2FUt43jzmEYwKU2xiGC', TRUE)
INSERT INTO NACOS.ROLES (username, role) VALUES ('nacos', 'ROLE_ADMIN')
SELECT username, enabled FROM NACOS.USERS
SELECT username, role FROM NACOS.ROLES
