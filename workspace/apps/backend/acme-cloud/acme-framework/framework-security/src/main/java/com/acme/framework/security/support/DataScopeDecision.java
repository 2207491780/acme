package com.acme.framework.security.support;

import java.util.Collection;
import java.util.Set;

/**
 * 数据范围判定结果（REQ-20261114-120104 五档 → 过滤条件载体，Q-D09 仅承载判定要素）：
 * <ul>
 *   <li>{@code ALL}：无限制（两集合恒空，{@link #unrestricted()}=true）；</li>
 *   <li>{@code DEPT / DEPT_AND_CHILD / CUSTOM}：{@code visibleDeptIds} 为业务侧已展开的目标部门集
 *       （DEPT_AND_CHILD 含子树展开），SQL 侧按 {@code dept_id IN} 落条件；</li>
 *   <li>{@code SELF}：{@code visibleUserIds} 仅当前主体（主体 id 形态与消费域对齐——
 *       用户域为主键数值、审计域为 operator_id 字符串，统一承载为字符串集）；</li>
 *   <li>多角色合并：档位标签取最宽档，可见集取各角色并集（并集语义 ≥ 任一单角色）。</li>
 * </ul>
 */
public record DataScopeDecision(DataScope scope, Set<Long> visibleDeptIds, Set<String> visibleUserIds) {

    public DataScopeDecision {
        visibleDeptIds = visibleDeptIds == null ? Set.of() : Set.copyOf(visibleDeptIds);
        visibleUserIds = visibleUserIds == null ? Set.of() : Set.copyOf(visibleUserIds);
        if (scope == null) {
            throw new IllegalArgumentException("数据范围档位不能为空（五档契约）");
        }
    }

    /** 全部数据（无过滤条件；与实例判定 {@link #unrestricted()} 同名异形，工厂取 all 避免擦除冲突）。 */
    public static DataScopeDecision all() {
        return new DataScopeDecision(DataScope.ALL, Set.of(), Set.of());
    }

    /**
     * 构造受限档判定（部门集 / 用户集按档位语义至少一侧非空）：工厂判空归一后转集合组件
     * （防御拷贝），null 容忍与紧凑构造口径一致。
     */
    public static DataScopeDecision of(DataScope scope, Collection<Long> deptIds, Collection<String> userIds) {
        return new DataScopeDecision(scope,
                deptIds == null ? Set.of() : Set.copyOf(deptIds),
                userIds == null ? Set.of() : Set.copyOf(userIds));
    }

    /** 是否无限制（ALL 档，消费方直接跳过过滤）。 */
    public boolean unrestricted() {
        return scope == DataScope.ALL;
    }
}
