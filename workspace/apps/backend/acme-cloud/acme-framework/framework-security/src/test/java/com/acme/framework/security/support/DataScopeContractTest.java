package com.acme.framework.security.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据范围五档契约测试（REQ-20261114-120104）：档位宽序、宽松解析、判定载体防御拷贝与空集归一。
 */
class DataScopeContractTest {

    @Test
    void rankOrderMatchesWideSemantics() {
        assertThat(DataScope.ALL.rank()).isGreaterThan(DataScope.DEPT_AND_CHILD.rank());
        assertThat(DataScope.DEPT_AND_CHILD.rank()).isGreaterThan(DataScope.DEPT.rank());
        assertThat(DataScope.DEPT.rank()).isGreaterThan(DataScope.CUSTOM.rank());
        assertThat(DataScope.CUSTOM.rank()).isGreaterThan(DataScope.SELF.rank());
        assertThat(DataScope.widest(DataScope.SELF, DataScope.DEPT)).isEqualTo(DataScope.DEPT);
    }

    @Test
    void parseToleratesBlankAndUnknown() {
        assertThat(DataScope.parse("CUSTOM")).isEqualTo(DataScope.CUSTOM);
        assertThat(DataScope.parse(" CUSTOM ")).isEqualTo(DataScope.CUSTOM);
        assertThat(DataScope.parse("TENANT")).isNull();
        assertThat(DataScope.parse(null)).isNull();
        assertThat(DataScope.parse(" ")).isNull();
        assertThat(DataScope.DEFAULT).isEqualTo(DataScope.ALL);
    }

    @Test
    void decisionNormalizesCollectionsAndGuardsScope() {
        DataScopeDecision decision = DataScopeDecision.of(DataScope.CUSTOM, null, null);
        assertThat(decision.visibleDeptIds()).isEmpty();
        assertThat(decision.visibleUserIds()).isEmpty();
        assertThat(decision.unrestricted()).isFalse();

        DataScopeDecision withSets = DataScopeDecision.of(DataScope.DEPT, List.of(1L, 1L), List.of("42"));
        assertThat(withSets.visibleDeptIds()).containsExactly(1L);
        assertThat(withSets.visibleUserIds()).containsExactly("42");

        assertThatThrownBy(() -> new DataScopeDecision(null, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unrestrictedDecisionHasEmptySets() {
        DataScopeDecision decision = DataScopeDecision.all();
        assertThat(decision.scope()).isEqualTo(DataScope.ALL);
        assertThat(decision.unrestricted()).isTrue();
        assertThat(decision.visibleDeptIds()).isEmpty();
        assertThat(decision.visibleUserIds()).isEmpty();
    }
}
