package com.acme.framework.flex.internal;

import com.acme.framework.core.support.ApplicationContexts;
import com.acme.framework.flex.config.FlexProperties;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.logicdelete.LogicDeleteManager;
import com.mybatisflex.core.query.QueryWrapper;

import java.util.function.Supplier;

/**
 * 软删除执行支撑（FR-009，10.3.1）：仓储删除路径统一入口。
 * 开关 {@code acme.flex.soft-delete.enabled}（默认 true）：开启走逻辑删除（删除=置 deleted=true，
 * 天然幂等）；关闭或绑定表强制物理删除场景经 {@link LogicDeleteManager#execWithoutLogicDelete} 旁路
 * （绑定表唯一键决定重绑必须物理删除，否则唯一键冲突）。
 * 开关读取（容器未就绪按开启处理，缺省口径与配置默认值一致）。
 */
public final class FlexDeletes {

    private FlexDeletes() {
    }

    /** 软删除是否启用（{@code acme.flex.soft-delete.enabled}，默认 true）。 */
    public static boolean softDeleteEnabled() {
        try {
            return ApplicationContexts.getBean(FlexProperties.class).getSoftDelete().isEnabled();
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** 按主键删除：软删除开启走逻辑删除，关闭走物理删除。 */
    public static <T> int deleteById(BaseMapper<T> mapper, Object id) {
        Long key = toId(id);
        if (softDeleteEnabled()) {
            return mapper.deleteById(key);
        }
        return execWithoutLogicDelete(() -> mapper.deleteById(key));
    }

    /** 按条件删除：软删除开启走逻辑删除，关闭走物理删除。 */
    public static <T> int deleteByQuery(BaseMapper<T> mapper, QueryWrapper query) {
        if (softDeleteEnabled()) {
            return mapper.deleteByQuery(query);
        }
        return execWithoutLogicDelete(() -> mapper.deleteByQuery(query));
    }

    /** 强制物理删除（忽略软删除开关；绑定表唯一键重绑等必须物理删除的场景）。 */
    public static <T> int forceDeleteByQuery(BaseMapper<T> mapper, QueryWrapper query) {
        return execWithoutLogicDelete(() -> mapper.deleteByQuery(query));
    }

    /** 在旁路中执行任意删除逻辑（绑定表自定义物理删除路径）。 */
    public static void execWithoutLogicDelete(Runnable runnable) {
        LogicDeleteManager.execWithoutLogicDelete(runnable);
    }

    /** 在旁路中执行任意删除逻辑（带返回值）。 */
    public static <T> T execWithoutLogicDelete(Supplier<T> supplier) {
        return LogicDeleteManager.execWithoutLogicDelete(supplier);
    }

    private static Long toId(Object id) {
        if (id instanceof Long value) {
            return value;
        }
        if (id instanceof Number value) {
            return value.longValue();
        }
        throw new IllegalArgumentException("主键必须是数值型（12.3.1 雪花主键）: " + id);
    }
}
