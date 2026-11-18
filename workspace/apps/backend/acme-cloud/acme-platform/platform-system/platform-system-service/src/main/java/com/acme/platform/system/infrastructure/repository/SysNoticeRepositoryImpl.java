package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;

import com.acme.platform.system.domain.SysNotice;
import com.acme.platform.system.infrastructure.mapper.SysNoticeMapper;
import org.springframework.stereotype.Repository;

/** 通知公告仓库实现（MyBatis-Flex 底座；租户过滤由框架注入）。 */
@Repository
public class SysNoticeRepositoryImpl extends FlexRepositorySupport<SysNotice>
        implements SysNoticeRepository {

    public SysNoticeRepositoryImpl(SysNoticeMapper mapper) {
        super(mapper);
    }
}
