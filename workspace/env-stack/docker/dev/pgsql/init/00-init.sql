-- Acme PostgreSQL 初始化脚本（仅在数据卷首次初始化时执行）
--
-- 数据独占：每个服务独占数据库（或独立 schema），禁止跨服务共享表（Backend.md 12.1.1、M-03）。
-- 本脚本不预置任何服务库：新增服务时按 Backend.md 18.1.1 第 7 步创建独立 schema/库与账号。
-- 公共库 ${POSTGRES_DB} 由镜像环境变量创建，仅作本地管理与连接验证用。
--
-- 迁移约定：各服务库内变更用 Flyway 版本化脚本管理（Backend.md 12.3.3），不手工改表。

SET TIME ZONE 'Asia/Shanghai';
