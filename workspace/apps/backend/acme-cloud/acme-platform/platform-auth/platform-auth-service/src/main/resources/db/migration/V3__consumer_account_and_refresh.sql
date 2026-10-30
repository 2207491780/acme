-- C 端消费者认证域迁移（consumer-auth-refresh-dataperm）：
-- 1) C 端账户表（验证码登录即注册；凭据域闭环，档案归后续 consumer 业务服务）；
-- 2) 会话底账补刷新令牌列（C 端双令牌：access + refresh 一次性轮换，11.4）。

CREATE TABLE auth_consumer_account (
    id            bigint PRIMARY KEY,
    phone         varchar(16)  NOT NULL,
    status        varchar(16)  NOT NULL,
    registered_at timestamptz  NOT NULL,
    last_login_at timestamptz,
    created_at    timestamptz  NOT NULL,
    created_by    varchar(64)  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    updated_by    varchar(64)  NOT NULL,
    version       bigint       NOT NULL,
    deleted       boolean      NOT NULL,
    CONSTRAINT uk_auth_consumer_phone UNIQUE (phone)
);

ALTER TABLE auth_session ADD COLUMN refresh_token_id varchar(64);
