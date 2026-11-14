package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;

import com.acme.platform.system.domain.SysNotice;

/** 通知公告仓库（租户归属，租户列自动过滤；CRUD 走 FlexRepository 基面）。 */
public interface SysNoticeRepository extends FlexRepository<SysNotice> {
}
