-- auth_session.refresh_token_id 索引（review 修复：刷新轮换按 refresh_token_id 定位活跃会话
-- 与轮换代账，此前无索引全表扫描；会话行按刷新令牌 TTL（14 天）持续增长，扫描成本随时间放大）。
CREATE INDEX idx_auth_session_refresh_token ON auth_session (refresh_token_id);
