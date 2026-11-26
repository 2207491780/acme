package com.acme.framework.tenant.internal;

import com.acme.common.context.TenantContext;
import com.acme.framework.tenant.config.TenantProperties;
import com.acme.framework.tenant.support.TenantBypassScope;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库 RLS 会话变量传播（REQ-20261125-001737 AC-1/4/5/8）：DataSource 动态代理包装，
 * 在连接上的首条业务 SQL 执行前把 TenantContext / 旁路状态写入 PostgreSQL 会话变量。
 *
 * <p>语义对齐 MyBatis-Flex 租户层：租户编码取 {@link TenantContext}（trim 后），未绑定取
 * {@link FlexTenantFactory#UNBOUND_TENANT} 哨兵（不匹配任何真实租户，fail-closed）；
 * {@link TenantBypassScope} 激活时 {@code app.tenant_bypass='on'}（关闭时显式置 off，防止同一
 * 事务内旁路关闭后残留）。写入用 {@code set_config(key, value, true)}，等价于 {@code SET LOCAL}：
 * 仅事务生命周期有效，事务结束由 PG 自动清除，无跨事务残留。</p>
 *
 * <p>仅对 URL 以 {@code jdbc:postgresql} 开头的连接生效（按 URL 判定，H2/其他驱动自动跳过）；
 * 非事务连接（autoCommit=true）跳过——SET LOCAL 在自动提交下无效，且 contract 只钉死事务内语义。</p>
 *
 * <p>实现形态照 framework-mybatis-flex SlowSqlSupport 先例（无新三方依赖）：DataSource → Connection →
 * Statement 三层 JDK 动态代理；会话变量为参数化 set_config（避免租户编码中的引号注入）。
 * 状态按连接缓存，commit/rollback/close/setAutoCommit 时清空，保证池化连接跨事务不残留。</p>
 */
public final class RlsPropagationSupport {

    /** 租户编码会话变量（RLS 策略读取）。 */
    private static final String TENANT_SETTING = "app.tenant_id";

    /** 旁路会话变量（RLS 策略读取）。 */
    private static final String BYPASS_SETTING = "app.tenant_bypass";

    private static final String BYPASS_ON = "on";
    private static final String BYPASS_OFF = "off";
    private static final String POSTGRES_URL_PREFIX = "jdbc:postgresql";

    private RlsPropagationSupport() {
    }

    /** 包装标记（避免重复包装，同 SlowSqlSupport.Proxied）。 */
    public interface Proxied {
    }

    /** 包装 DataSource（仅 PG 连接实际启用传播；非 PG 直接透传原始连接）。 */
    public static DataSource wrap(DataSource delegate, TenantProperties properties) {
        return (DataSource) Proxy.newProxyInstance(
                RlsPropagationSupport.class.getClassLoader(),
                new Class<?>[]{DataSource.class, Proxied.class},
                new DataSourceHandler(delegate, properties));
    }

    /** DataSource 代理：缓存 URL 判定结果，getConnection 时按需套 Connection 代理。 */
    private static final class DataSourceHandler implements InvocationHandler {

        private final DataSource delegate;
        private final TenantProperties properties;
        private volatile Boolean postgres;

        private DataSourceHandler(DataSource delegate, TenantProperties properties) {
            this.delegate = delegate;
            this.properties = properties;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!"getConnection".equals(method.getName())) {
                return invokeDelegate(delegate, method, args);
            }
            Connection raw = (Connection) invokeDelegate(delegate, method, args);
            if (postgres == null) {
                postgres = isPostgres(raw);
            }
            if (!postgres || !properties.getRls().isPropagation()) {
                return raw;
            }
            return Proxy.newProxyInstance(RlsPropagationSupport.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, new ConnectionHandler(raw));
        }
    }

    /** Connection 代理：事务边界重置缓存；Statement 创建时套执行前钩子。 */
    private static final class ConnectionHandler implements InvocationHandler {

        private final Connection raw;
        private String appliedTenant;
        private String appliedBypass;

        private ConnectionHandler(Connection raw) {
            this.raw = raw;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("setAutoCommit".equals(name) || "commit".equals(name)
                    || "rollback".equals(name) || "close".equals(name)) {
                Object result = invokeDelegate(raw, method, args);
                appliedTenant = null;
                appliedBypass = null;
                return result;
            }
            if ("createStatement".equals(name) || "prepareStatement".equals(name)
                    || "prepareCall".equals(name)) {
                Object statement = invokeDelegate(raw, method, args);
                Class<?> type = "prepareCall".equals(name) ? CallableStatement.class
                        : "prepareStatement".equals(name) ? PreparedStatement.class : Statement.class;
                return Proxy.newProxyInstance(RlsPropagationSupport.class.getClassLoader(),
                        new Class<?>[]{type}, new StatementHandler((Statement) statement, this));
            }
            return invokeDelegate(raw, method, args);
        }

        /** 首条业务 SQL 执行前写入会话变量；值未变时不重复往返。 */
        private void ensure() throws SQLException {
            if (raw.getAutoCommit()) {
                // 非事务连接：SET LOCAL 无效，跳过（AC-1 仅钉死事务内语义）。
                return;
            }
            TenantContext context = TenantContext.get();
            String tenant = context == null ? null : context.tenantId();
            if (tenant == null || tenant.isBlank()) {
                tenant = FlexTenantFactory.UNBOUND_TENANT;
            } else {
                tenant = tenant.trim();
            }
            String bypass = TenantBypassScope.isActive() ? BYPASS_ON : BYPASS_OFF;
            if (tenant.equals(appliedTenant) && bypass.equals(appliedBypass)) {
                return;
            }
            // 先清空状态：任一写入失败时下次执行重试，不留半套会话变量（fail-closed）。
            appliedTenant = null;
            appliedBypass = null;
            applySetting(raw, TENANT_SETTING, tenant);
            applySetting(raw, BYPASS_SETTING, bypass);
            appliedTenant = tenant;
            appliedBypass = bypass;
        }
    }

    /** Statement 代理：execute* 前触发会话变量写入（覆盖 JdbcTemplate/MyBatis-Flex 直连 SQL）。 */
    private static final class StatementHandler implements InvocationHandler {

        private final Statement raw;
        private final ConnectionHandler owner;

        private StatementHandler(Statement raw, ConnectionHandler owner) {
            this.raw = raw;
            this.owner = owner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().startsWith("execute")) {
                owner.ensure();
            }
            return invokeDelegate(raw, method, args);
        }
    }

    /** 参数化 set_config(key, value, true) ≡ SET LOCAL，仅当前事务有效。 */
    private static void applySetting(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.execute();
        }
    }

    /** URL 判定（AC-4：仅 jdbc:postgresql 生效）。 */
    private static boolean isPostgres(Connection raw) throws SQLException {
        String url = raw.getMetaData().getURL();
        return url != null && url.startsWith(POSTGRES_URL_PREFIX);
    }

    /** 反射调用统一拆包（代理方法抛 Throwable，不吞 SQLException）。 */
    private static Object invokeDelegate(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
