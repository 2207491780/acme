package com.acme.framework.core.support;

import com.acme.common.core.BizException;
import com.acme.common.core.CommonError;
import com.acme.framework.core.internal.ApplicationContextRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

/**
 * 应用上下文访问工具（FR-004）：按类型/名称取容器组件 + 按配置键读取配置（BR-021）。
 *
 * <p>失败语义（BR-022、AC-019）：组件不存在、上下文未就绪抛 {@link BizException}（DQ-002 错误码）；
 * 配置缺键按可选/必得二分（DQ-003）。消息只给固定文案，FQCN/配置键仅进 debug 日志（10.5.4）。</p>
 */
public final class ApplicationContexts {

    private static final Logger log = LoggerFactory.getLogger(ApplicationContexts.class);

    private ApplicationContexts() {
    }

    /** 按类型取容器组件（FR-004）。 */
    public static <T> T getBean(Class<T> beanType) {
        if (beanType == null) {
            throw new IllegalArgumentException("beanType 不能为空");
        }
        try {
            return context().getBean(beanType);
        } catch (NoSuchBeanDefinitionException ex) {
            log.debug("组件不存在：{}", beanType.getName(), ex);
            throw new BizException(CommonError.NOT_FOUND, "组件不存在", ex);
        } catch (BeansException ex) {
            log.debug("获取组件失败：{}", beanType.getName(), ex);
            throw new BizException(CommonError.INTERNAL_ERROR, "获取组件失败", ex);
        }
    }

    /** 按名称与类型取容器组件（FR-004）。 */
    public static <T> T getBean(String name, Class<T> beanType) {
        if (name == null || name.isBlank() || beanType == null) {
            throw new IllegalArgumentException("name/beanType 不能为空");
        }
        try {
            return context().getBean(name, beanType);
        } catch (NoSuchBeanDefinitionException ex) {
            log.debug("组件不存在：{}", name, ex);
            throw new BizException(CommonError.NOT_FOUND, "组件不存在", ex);
        } catch (BeansException ex) {
            log.debug("获取组件失败：{}", name, ex);
            throw new BizException(CommonError.INTERNAL_ERROR, "获取组件失败", ex);
        }
    }

    /** 按名称取容器组件（FR-004）。 */
    public static Object getBean(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        try {
            return context().getBean(name);
        } catch (NoSuchBeanDefinitionException ex) {
            log.debug("组件不存在：{}", name, ex);
            throw new BizException(CommonError.NOT_FOUND, "组件不存在", ex);
        } catch (BeansException ex) {
            log.debug("获取组件失败：{}", name, ex);
            throw new BizException(CommonError.INTERNAL_ERROR, "获取组件失败", ex);
        }
    }

    /** 可选读取配置（FR-004；DQ-003）：缺键返回 null。 */
    public static String getProperty(String key) {
        return context().getEnvironment().getProperty(requireKey(key));
    }

    /** 必得读取配置（FR-004；DQ-003）：缺键抛明确异常。 */
    public static String getRequiredProperty(String key) {
        String value = getProperty(key);
        if (value == null) {
            log.debug("配置项缺失：{}", key);
            throw new BizException(CommonError.NOT_FOUND, "配置项缺失");
        }
        return value;
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key 不能为空");
        }
        return key;
    }

    private static ApplicationContext context() {
        ApplicationContext applicationContext = ApplicationContextRegistry.get();
        if (applicationContext == null) {
            throw new BizException(CommonError.INTERNAL_ERROR, "应用上下文未就绪");
        }
        return applicationContext;
    }
}
