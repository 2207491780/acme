package com.acme.framework.tx.internal;

import com.acme.common.core.BizException;
import com.acme.framework.tx.config.TxProperties;
import com.acme.framework.tx.support.TxError;
import org.springframework.beans.factory.InitializingBean;

/**
 * Seata 装配校验（FR-021、架构设计 13.3）：显式开启 {@code acme.tx.global.enabled} 后校验基础配置齐备，
 * 配置非法或 Seata 基础设施缺失启动期显式失败（SYS-19），不得静默降级为「看似分布式事务实则未生效」。
 */
public class SeataAssemblyValidator implements InitializingBean {

    /** Seata TM/RM 接线标识类（io.seata，版本随 spring-cloud-alibaba BOM）。 */
    static final String DEFAULT_REQUIRED_TYPE = "io.seata.spring.annotation.GlobalTransaction";

    private final TxProperties properties;
    private final String requiredType;

    public SeataAssemblyValidator(TxProperties properties) {
        this(properties, DEFAULT_REQUIRED_TYPE);
    }

    /** 供测试注入 requiredType（装配切片不依赖 Seata 类路径）。 */
    SeataAssemblyValidator(TxProperties properties, String requiredType) {
        this.properties = properties;
        this.requiredType = requiredType;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /** 装配校验（启动期执行）。 */
    void validate() {
        if (!properties.isGlobalEnabled()) {
            return;
        }
        if (properties.getTxGroup() == null || properties.getTxGroup().isBlank()) {
            throw new BizException(TxError.TX_CONFIG_INVALID, "事务分组不能为空（FR-021）");
        }
        if (properties.getTimeout() == null || properties.getTimeout().isNegative() || properties.getTimeout().isZero()) {
            throw new BizException(TxError.TX_CONFIG_INVALID, "事务超时预算须为正（FR-022）");
        }
        if (!present(requiredType)) {
            throw new BizException(TxError.SEATA_MISSING, "分布式事务基础设施缺失（架构设计 13.3）");
        }
    }

    private static boolean present(String type) {
        try {
            Class.forName(type, false, SeataAssemblyValidator.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
