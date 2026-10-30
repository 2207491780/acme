package com.acme.framework.jpa.internal;

import com.acme.framework.jpa.config.JpaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * 慢 SQL 与绑定参数日志（FR-013、6.7）：轻量 DataSource/PreparedStatement 动态代理包装（无新三方依赖），
 * 采集 SQL、绑定参数、耗时；超阈值输出 WARN（logger {@code acme.slow-sql}）；日志失败不影响 SQL 执行（FR-013 异常分支）。
 */
public final class SlowSqlSupport {

    /** 慢 SQL 日志 logger 名（6.7）。 */
    public static final String LOGGER_NAME = "acme.slow-sql";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private SlowSqlSupport() {
    }

    /** 包装标记（避免重复包装）。 */
    public interface Proxied {
    }

    /** 包装 DataSource（6.7）。 */
    public static DataSource wrap(DataSource delegate, JpaProperties properties) {
        JpaProperties.SlowSql config = properties.getSlowSql();
        return (DataSource) Proxy.newProxyInstance(
                SlowSqlSupport.class.getClassLoader(),
                new Class<?>[]{DataSource.class, Proxied.class},
                (proxy, method, args) -> invoke(delegate, method, args,
                        (self, m, a) -> "getConnection".equals(m.getName())
                                ? wrapConnection((Connection) m.invoke(delegate, a), config)
                                : null));
    }

    private static Connection wrapConnection(Connection delegate, JpaProperties.SlowSql config) {
        return (Connection) Proxy.newProxyInstance(
                SlowSqlSupport.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> invoke(delegate, method, args,
                        (self, m, a) -> {
                            if (!m.getName().startsWith("prepareStatement") || a == null || a.length == 0) {
                                return null;
                            }
                            String sql = String.valueOf(a[0]);
                            return wrapStatement((PreparedStatement) m.invoke(delegate, a), sql, config);
                        }));
    }

    private static PreparedStatement wrapStatement(PreparedStatement delegate, String sql,
                                                   JpaProperties.SlowSql config) {
        List<Object> params = new ArrayList<>();
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
                if ("setNull".equals(name)) {
                    params.add(null);
                } else {
                    params.add(args[1]);
                }
                return invoke(delegate, method, args, null);
            }
            if ("clearParameters".equals(name)) {
                params.clear();
                return invoke(delegate, method, args, null);
            }
            if (name.startsWith("execute")) {
                long start = System.currentTimeMillis();
                try {
                    return invoke(delegate, method, args, null);
                } finally {
                    report(sql, params, System.currentTimeMillis() - start, config);
                }
            }
            return invoke(delegate, method, args, null);
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                SlowSqlSupport.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, handler);
    }

    /** 超阈值输出 WARN（FR-013）；日志失败吞掉不影响 SQL 执行。 */
    private static void report(String sql, List<Object> params, long durationMs,
                               JpaProperties.SlowSql config) {
        try {
            if (durationMs < config.getThresholdMs()) {
                return;
            }
            if (config.isLogParameters()) {
                log.warn("慢 SQL ms={} sql={} params={}", durationMs, sql, SqlMasking.render(sql, params));
            } else {
                log.warn("慢 SQL ms={} sql={} paramsCount={}", durationMs, sql, params.size());
            }
        } catch (RuntimeException ex) {
            // 日志失败不得影响 SQL 执行（FR-013 异常分支）
        }
    }

    private static Object invoke(Object delegate, Method method, Object[] args,
                                 Fallback fallback) throws Throwable {
        if (fallback != null) {
            Object special = fallback.trySpecial(null, method, args);
            if (special != null) {
                return special;
            }
        }
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    @FunctionalInterface
    private interface Fallback {
        Object trySpecial(Object self, Method method, Object[] args) throws Throwable;
    }
}
