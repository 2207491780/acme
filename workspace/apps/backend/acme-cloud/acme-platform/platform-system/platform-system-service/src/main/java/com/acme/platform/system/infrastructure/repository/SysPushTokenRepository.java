package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.system.domain.SysPushToken;

import java.util.List;
import java.util.Optional;

/** 推送令牌仓库（token 全局唯一 upsert；删除物理删旁路，见 V21 注）。 */
public interface SysPushTokenRepository extends FlexRepository<SysPushToken> {

    /** 按令牌定位（upsert 判定数据源）。 */
    Optional<SysPushToken> findByToken(String token);

    /** 本人全部令牌（ExpoPushSender 扇出数据源）。 */
    List<SysPushToken> findByUserId(Long userId);

    /** 物理删除本人令牌（软删行占唯一键致重注册冲突；幂等——不存在视为已删 10.3.1）。 */
    void deleteByTokenAndUserId(String token, Long userId);
}
