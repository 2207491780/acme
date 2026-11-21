package com.acme.platform.workflow.infrastructure.mapper;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * jsonb ↔ String 类型处理器（platform-scheduler 同款照抄，REQ-20261119-082531 确认记录⑤口径）：
 * PostgreSQL 写入走 {@code setObject(i, value, Types.OTHER)}——驱动按 unknown 类型 OID 提交、
 * 服务端按列类型（jsonb）推断装载；直 setString 会因「expression is of type character varying」
 * 被服务端拒绝。其余方言（H2 切片等）退化 setString。读取统一 getString（jsonb 的文本形态即
 * 载荷字符串，往返无损）。实体侧注册：{@code @Column(value = "…", typeHandler = JsonbTypeHandler.class)}。
 */
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        if (isPostgresql(ps.getConnection())) {
            ps.setObject(i, parameter, Types.OTHER);
        } else {
            ps.setString(i, parameter);
        }
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }

    /** 方言探测（连接级元数据，代价可忽略）：仅 PostgreSQL 走 Types.OTHER 装载。 */
    private static boolean isPostgresql(java.sql.Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        return product != null && product.toLowerCase(java.util.Locale.ROOT).contains("postgresql");
    }
}
