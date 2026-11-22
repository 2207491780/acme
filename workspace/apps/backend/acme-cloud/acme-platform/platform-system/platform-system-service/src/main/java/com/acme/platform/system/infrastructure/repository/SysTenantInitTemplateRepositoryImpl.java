package com.acme.platform.system.infrastructure.repository;

import com.acme.framework.flex.repository.FlexRepositorySupport;
import com.acme.platform.system.domain.SysTenantInitTemplate;
import com.acme.platform.system.infrastructure.mapper.SysTenantInitTemplateMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 开通模板仓库实现（MyBatis-Flex 底座；平台级表不过滤租户）。 */
@Repository
public class SysTenantInitTemplateRepositoryImpl extends FlexRepositorySupport<SysTenantInitTemplate>
        implements SysTenantInitTemplateRepository {

    public SysTenantInitTemplateRepositoryImpl(SysTenantInitTemplateMapper mapper) {
        super(mapper);
    }

    @Override
    public Optional<SysTenantInitTemplate> findByCode(String code) {
        return Optional.ofNullable(
                mapper().selectOneByQuery(QueryWrapper.create().where(SysTenantInitTemplate::getCode).eq(code)));
    }

    @Override
    public boolean existsByCode(String code) {
        return mapper().selectCountByQuery(
                QueryWrapper.create().where(SysTenantInitTemplate::getCode).eq(code)) > 0;
    }

    @Override
    public boolean existsByCodeAndIdNot(String code, Long id) {
        return mapper().selectCountByQuery(QueryWrapper.create()
                .where(SysTenantInitTemplate::getCode).eq(code)
                .and(SysTenantInitTemplate::getId).ne(id)) > 0;
    }
}
