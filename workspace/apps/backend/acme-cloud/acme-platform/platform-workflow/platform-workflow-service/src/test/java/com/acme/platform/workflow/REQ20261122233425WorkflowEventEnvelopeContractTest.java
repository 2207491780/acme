package com.acme.platform.workflow;

import com.acme.common.serialization.JsonModules;
import com.acme.framework.stream.support.EventEnvelopes;
import com.acme.platform.workflow.api.dto.WorkflowEventEnvelope;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261122-233425（BPM-5）workflow 事件信封形状契约单测（契约确认④；AC-4 信封口径
 * + AC-9 消费侧冻结锚）：WorkflowEventEnvelope（workflow-api）为框架流 EventEnvelopes
 * 信封的 typed payload，载荷字段域=eventType/instanceId/definitionId/definitionKey/nodeId?/
 * businessKey/tenantId/traceId/occurredAt；信封壳=messageId/traceId/idempotentKey/version
 * （framework-stream EventEnvelopesTest 先例同口径）。
 *
 * <p>【落位说明】委派单口径=framework-workflow 单测，但 framework 层禁依赖服务模块
 * （4.4.4 enforcer；workflow-api 类不在其 classpath），且本载荷形状与发布器同域——
 * 落位 platform-workflow-service 测试域（唯一同时持有 workflow-api 与 framework-stream
 * 的测试面），注册表路由单测仍留 framework-workflow。tester 交付口径如实披露。</p>
 *
 * <p>【表面假定（失败即附原样输出归因 TL 裁决，tester 交付口径）】
 * A-1 record 组件名=契约④钉死字段名（accessor 以组件名直调）；组件次序未钉——构造不落
 *     代码（JSON 解析构建，规避次序猜测）；
 * A-2 occurredAt=Instant（交付 DTO 实形）；断言只钉非空与回读一致，不钉字面量格式；
 * A-3 消费侧解析失败语义（ENVELOPE_INVALID）不在本类——framework-stream 既有用例承载，
 *     本类只钉 workflow 载荷形状。</p>
 */
class REQ20261122233425WorkflowEventEnvelopeContractTest {

    private final JsonMapper mapper;

    REQ20261122233425WorkflowEventEnvelopeContractTest() {
        JsonMapper.Builder builder = JsonMapper.builder();
        for (JacksonModule module : JsonModules.standard()) {
            builder.addModule(module);
        }
        this.mapper = builder.build();
    }

    /** 载荷 JSON（确认④字段域；nodeId=节点级事件承载，实例级事件缺省）。 */
    private static final String ENVELOPE_JSON = """
            {"messageId":"m-233425","traceId":"trace-233425","idempotentKey":"i-233425",
             "version":"v1",
             "payload":{"eventType":"INSTANCE_STARTED","instanceId":42,
                        "definitionId":7,"definitionKey":"leave-v1",
                        "businessKey":"leave-233425","tenantId":"default",
                        "traceId":"trace-233425","occurredAt":"2026-11-22T12:00:00Z"}}
            """;

    @Test
    void should_parse_workflow_event_envelope_with_typed_payload_when_stream_envelope_used() {
        var parsed = EventEnvelopes.parse(ENVELOPE_JSON, WorkflowEventEnvelope.class, mapper);

        // 壳四字段（framework-stream 信封协议）
        assertThat(parsed.getMessageId()).isEqualTo("m-233425");
        assertThat(parsed.getTraceId()).isEqualTo("trace-233425");
        assertThat(parsed.getIdempotentKey()).isEqualTo("i-233425");
        assertThat(parsed.getVersion()).isEqualTo("v1");

        // 载荷字段域（确认④：eventType/instanceId/definitionId/definitionKey/businessKey/
        // tenantId/traceId/occurredAt；nodeId? 可空）
        WorkflowEventEnvelope payload = parsed.getPayload();
        assertThat(payload).as("typed payload 应解析为 WorkflowEventEnvelope").isNotNull();
        assertThat(payload.eventType()).isEqualTo("INSTANCE_STARTED");
        assertThat(payload.instanceId()).isEqualTo(42L);
        assertThat(payload.businessKey()).isEqualTo("leave-233425");
        assertThat(payload.tenantId()).isEqualTo("default");
        assertThat(payload.traceId()).isEqualTo("trace-233425");
        assertThat(payload.definitionKey()).isEqualTo("leave-v1");
        assertThat(payload.occurredAt()).as("occurredAt 非空（type+occurredAt+payload 口径）")
                .isNotNull();
    }

    @Test
    void should_round_trip_payload_without_field_loss_when_envelope_reserialized() {
        var first = EventEnvelopes.parse(ENVELOPE_JSON, WorkflowEventEnvelope.class, mapper);

        // newEnvelope 缺省口径（EventEnvelopesTest 先例：idempotentKey 缺省=messageId）
        var rebuilt = EventEnvelopes.newEnvelope(first.getPayload(), "v1");
        assertThat(rebuilt.getIdempotentKey()).isEqualTo(rebuilt.getMessageId());
        assertThat(rebuilt.getTraceId()).isNotBlank();
        assertThat(rebuilt.getVersion()).isEqualTo("v1");

        // 序列化 → 回读：载荷字段零丢失（消费侧冻结口径，AC-9）
        String wire = mapper.writeValueAsString(rebuilt);
        assertThat(wire)
                .as("wire 应含壳协议字段与载荷 eventType/occurredAt")
                .contains("\"messageId\"").contains("\"payload\"")
                .contains("\"eventType\"").contains("\"occurredAt\"");
        var reparsed = EventEnvelopes.parse(wire, WorkflowEventEnvelope.class, mapper);
        assertThat(reparsed.getPayload()).isEqualTo(first.getPayload());
    }

    @Test
    void should_omit_node_id_for_instance_level_events_when_payload_has_no_node() {
        // nodeId? 语义：实例级事件（INSTANCE_STARTED/INSTANCE_COMPLETED）不携带 nodeId
        // （交付 DTO javadoc 同口径：「实例级事件 nodeId 为空」）
        String instanceLevelJson = """
                {"messageId":"m-nodeless","traceId":"trace-nodeless","idempotentKey":"i-nodeless",
                 "version":"v1",
                 "payload":{"eventType":"INSTANCE_COMPLETED","instanceId":43,
                            "definitionId":7,"definitionKey":"leave-v1",
                            "businessKey":"leave-233425","tenantId":"default",
                            "traceId":"trace-nodeless","occurredAt":"2026-11-22T12:00:00Z"}}
                """;
        var parsed = EventEnvelopes.parse(instanceLevelJson, WorkflowEventEnvelope.class, mapper);
        assertThat(parsed.getPayload().nodeId())
                .as("实例级事件 nodeId 应为空（确认④ nodeId? 可空语义）").isNull();
        assertThat(parsed.getPayload().eventType()).isEqualTo("INSTANCE_COMPLETED");
    }
}
