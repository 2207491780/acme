package com.acme.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261122-233425（BPM-5）ServiceTask 委托协议体镜像锁定单测（SDK 消费侧冻结契约的
 * 防漂移锚；委派单钉死事实「ServiceTaskDispatchPayload 字段冻结勿在断言外依赖扩展」+ AC-9
 * 同族口径）：framework-workflow 消费侧镜像 record（framework 层禁依赖服务模块，4.4.4
 * enforcer——按冻结字段名镜像自建解码形态）与 platform-workflow-api 冻结源 record 的
 * 组件名/顺序逐字一致（Jackson 按名解码与下发侧序列化互认的形状前提）。
 *
 * <p>落位 acme-bootstrap：唯一同时持有 workflow-api（经 platform-workflow-service）与
 * framework-workflow（经 business-leave）classpath 的测试面；纯反射零 Spring 上下文。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 两侧均为 record（交付实形）；组件顺序差异=漂移（解码 record 顺序虽不参与按名解码，
 *     但镜像口径钉「字段名/语义/顺序逐字对齐」——顺序漂移红=实现披露差异）；</p>
 */
class REQ20261122233425ServiceTaskPayloadMirrorTest {

    /** 冻结源（platform-workflow-api，BPM-1 钉死；注释钉死「字段名不得变更」）。 */
    private static final String FROZEN =
            "com.acme.platform.workflow.api.dto.ServiceTaskDispatchPayload";

    /** 消费侧镜像（framework-workflow support，按冻结字段名镜像自建）。 */
    private static final String MIRROR =
            "com.acme.framework.workflow.support.ServiceTaskDispatchPayload";

    @Test
    void should_mirror_frozen_payload_field_names_and_order_when_sdk_decodes() throws Exception {
        RecordComponent[] frozen = Class.forName(FROZEN).getRecordComponents();
        RecordComponent[] mirror = Class.forName(MIRROR).getRecordComponents();

        assertThat(frozen).as("冻结源 record 组件应存在").isNotEmpty();
        assertThat(mirror).as("镜像 record 组件应存在").isNotEmpty();

        String[] frozenNames = names(frozen);
        String[] mirrorNames = names(mirror);
        assertThat(mirrorNames)
                .as("SDK 消费侧镜像字段应与冻结契约逐字一致（名称+顺序；BPM-1 冻结）")
                .containsExactly(frozenNames);
    }

    private static String[] names(RecordComponent[] components) {
        String[] names = new String[components.length];
        for (int i = 0; i < components.length; i++) {
            names[i] = components[i].getName();
        }
        return names;
    }
}
