package com.acme.framework.flex;

import com.acme.framework.flex.app.FlexAuditProbe;
import com.acme.framework.flex.app.FlexAuditProbeMapper;
import com.acme.framework.flex.app.FlexTestApplication;
import com.acme.framework.flex.internal.FlexDeletes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 软删除关闭分支测试（FR-009 异常分支、10.3.1）：{@code acme.flex.soft-delete.enabled=false}
 * 时删除走物理删除（旁路 LogicDeleteManager）。
 */
@SpringBootTest(classes = FlexTestApplication.class, properties = {
        "acme.flex.slow-sql.enabled=false",
        "acme.flex.id.worker-id=1",
        "acme.flex.soft-delete.enabled=false"
})
class FlexPhysicalDeleteTest {

    @Autowired
    FlexAuditProbeMapper mapper;

    @Test
    void softDeleteToggleReadsFalse() {
        assertThat(FlexDeletes.softDeleteEnabled()).isFalse();
    }

    @Test
    void deleteBecomesPhysical() {
        FlexAuditProbe probe = new FlexAuditProbe("gone-physical", 1L, 1L);
        assertThat(mapper.insert(probe)).isEqualTo(1);
        Long id = probe.getId();

        assertThat(FlexDeletes.deleteById(mapper, id)).isEqualTo(1);
        // 物理删除：旁路查询也查不到该行（数据已不在库）
        FlexAuditProbe bypass = FlexDeletes.execWithoutLogicDelete(() -> mapper.selectOneById(id));
        assertThat(bypass).isNull();
    }
}
