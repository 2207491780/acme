package com.acme.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261127-161433 P3-09（B4-消息 面关联，双 api 模块同名形状 record 镜像锁定）：
 * platform-infra-api（internal 统计契约冻结源）与 platform-system-api（dashboard 消费侧
 * 镜像，两 api 模块不互相依赖、消费方分别映射）的 {@code ActionCount} record——组件名/类型/
 * 顺序逐字一致（Jackson 按名解码与 wire 形态互认的形状前提；漂移=一侧改名/扩字段未同步）。
 *
 * <p>落位 acme-bootstrap：唯一同时持有两 api 模块 classpath 的测试面；纯反射零 Spring 上下文。
 * 对齐先例 {@code REQ20261122233425ServiceTaskPayloadMirrorTest}。</p>
 */
class REQ20261127161433ActionCountMirrorTest {

    /** 冻结源（platform-infra-api internal 统计契约）。 */
    private static final String FROZEN = "com.acme.platform.infra.api.ActionCount";

    /** 消费侧镜像（platform-system-api dashboard 视图）。 */
    private static final String MIRROR = "com.acme.platform.system.api.dto.ActionCount";

    @Test
    void should_mirror_action_count_field_names_types_and_order_when_dashboard_consumes() throws Exception {
        RecordComponent[] frozen = Class.forName(FROZEN).getRecordComponents();
        RecordComponent[] mirror = Class.forName(MIRROR).getRecordComponents();

        assertThat(frozen).as("冻结源 record 组件应存在").isNotEmpty();
        assertThat(mirror).as("镜像 record 组件应存在").isNotEmpty();
        assertThat(mirror.length).as("组件数一致（任一侧扩/删字段未同步即红）").isEqualTo(frozen.length);
        for (int i = 0; i < frozen.length; i++) {
            assertThat(mirror[i].getName())
                    .as("组件[%d] 名逐字一致（frozen=%s）", i, frozen[i].getName())
                    .isEqualTo(frozen[i].getName());
            assertThat(mirror[i].getType())
                    .as("组件[%d] 类型一致（wire 形态前提）", i)
                    .isEqualTo(frozen[i].getType());
        }
    }
}
