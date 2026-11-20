package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepository;
import com.acme.platform.system.domain.SysCapability;

import java.util.Collection;
import java.util.List;

/** 服务能力仓库（平台级目录表，不参与租户过滤；REQ-20261121-054613）。 */
public interface SysCapabilityRepository extends FlexRepository<SysCapability> {

    /** 全量启用能力（id asc；目录端点与"未绑定=全部启用"缺省集共用，确认③④）。 */
    List<SysCapability> findEnabled();

    /** 启用能力按 id 集取（绑定集→能力码解析，id asc 稳定输出）。 */
    List<SysCapability> findEnabledByIdIn(Collection<Long> ids);

    /** 启用能力按码集取（套餐保存时能力码存在性校验，确认④）。 */
    List<SysCapability> findEnabledByCodeIn(Collection<String> codes);
}
