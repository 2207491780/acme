-- 个人中心——资料 / 偏好 / 推送令牌（REQ-20261119-082531 确认记录①⑤；迁移段位 system V21）。
-- 幂等口径：DDL 经 Flyway 版本化单次执行（同 V16/V17 ALTER 口径，不使用 IF NOT EXISTS）；无种子行。
-- sys_user.avatar_url varchar(512) 可空：头像文件服务 URL 或相对地址（前端经 PUT /me 回填——
-- 头像上传端点承载于 platform-infra POST /admin/v1/infra/me/avatar，TL 决策①：system 域无存储依赖）。
-- 偏好与推送令牌为用户域数据（非租户归属——按 user_id 全局定位，Q-D04 同 auth_session 口径；
-- AuditBase 通用列：id 雪花主键 / created_* / updated_* / version / deleted）。
-- sys_user_preference.prefs 为 jsonb：实体侧 String 经 JsonbTypeHandler 映射（PG jsonb 写入用
-- PGobject、其余方言退化 setString），载荷形态 {"messagePush": boolean}（确认记录⑤）。
-- sys_push_token 删除走物理删（FlexDeletes.forceDeleteByQuery）：软删行占 token 唯一键致重注册冲突，
-- 对齐 sys_user_role 绑定表物理删口径（flex-test-assembly #4）。

ALTER TABLE sys_user ADD COLUMN avatar_url varchar(512);

CREATE TABLE sys_user_preference (
    id          bigint      PRIMARY KEY,
    user_id     bigint      NOT NULL,
    prefs       jsonb       NOT NULL,
    created_at  timestamptz NOT NULL,
    created_by  varchar(64) NOT NULL,
    updated_at  timestamptz NOT NULL,
    updated_by  varchar(64) NOT NULL,
    version     bigint      NOT NULL,
    deleted     boolean     NOT NULL,
    CONSTRAINT uk_sys_user_preference_user UNIQUE (user_id)
);

CREATE TABLE sys_push_token (
    id          bigint       PRIMARY KEY,
    user_id     bigint       NOT NULL,
    token       varchar(512) NOT NULL,
    platform    varchar(8)   NOT NULL,
    created_at  timestamptz  NOT NULL,
    created_by  varchar(64)  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    updated_by  varchar(64)  NOT NULL,
    version     bigint       NOT NULL,
    deleted     boolean      NOT NULL,
    CONSTRAINT uk_sys_push_token_token UNIQUE (token)
);

CREATE INDEX idx_sys_push_token_user ON sys_push_token (user_id);
