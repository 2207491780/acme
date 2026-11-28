package com.acme.platform.workflow.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261128-051442（AC-11）：WorkflowErrorCodes 全量常量快照测试（非 MSW）。
 * 契约附录 A.5 为唯一权威清单：反射枚举全部 public static final String 常量（按名排序），
 * 断言 (name→value) 全集精确等于附录 A workflow 切片——双向锁：缺常量、多常量、任一值漂移皆红。
 * 决策①：后端 Result.code() 与一切错误码定义零改动，本测试仅锁定现状 wire 真值。
 */
class REQ20261128051442WorkflowErrorCodesSnapshotTest {

    /** 附录 A.5 切片（platform-workflow 段，wire 值 ← 常量名，14 码）。 */
    private static final Map<String, String> CONTRACT_APPENDIX_A_WORKFLOW = Map.ofEntries(
            // ---- 01 流程定义 ----
            Map.entry("WORKFLOW_KEY_DUPLICATE", "platform-workflow-01-0001"),
            Map.entry("WORKFLOW_XML_INVALID", "platform-workflow-01-0002"),
            Map.entry("WORKFLOW_VALIDATION_FAILED", "platform-workflow-01-0003"),
            Map.entry("WORKFLOW_DEFINITION_NOT_FOUND", "platform-workflow-01-0004"),
            Map.entry("WORKFLOW_DEFINITION_DISABLED", "platform-workflow-01-0005"),
            // ---- 02 流程实例 ----
            Map.entry("WORKFLOW_INSTANCE_NOT_FOUND", "platform-workflow-02-0001"),
            Map.entry("WORKFLOW_INSTANCE_STATE_CONFLICT", "platform-workflow-02-0002"),
            Map.entry("WORKFLOW_DEFINITION_NOT_DEPLOYED", "platform-workflow-02-0003"),
            Map.entry("WORKFLOW_BUSINESS_KEY_REQUIRED", "platform-workflow-02-0004"),
            // ---- 03 任务 ----
            Map.entry("WORKFLOW_TASK_NOT_FOUND", "platform-workflow-03-0001"),
            Map.entry("WORKFLOW_TASK_STATE_CONFLICT", "platform-workflow-03-0002"),
            Map.entry("WORKFLOW_ASSIGNEE_MISMATCH", "platform-workflow-03-0003"),
            Map.entry("WORKFLOW_DELEGATE_STATE_INVALID", "platform-workflow-03-0004"),
            Map.entry("WORKFLOW_NO_ASSIGNEE_RESOLVED", "platform-workflow-03-0005"));

    @Test
    void workflowErrorCodesConstantsExactlyMatchContractAppendixA() throws IllegalAccessException {
        assertThat(publicStringConstants(WorkflowErrorCodes.class))
                .containsExactlyInAnyOrderEntriesOf(CONTRACT_APPENDIX_A_WORKFLOW);
    }

    /** 反射枚举目标类全部 public static final String 常量（按名排序，值为 wire 真值）。 */
    private static Map<String, String> publicStringConstants(Class<?> type) throws IllegalAccessException {
        Map<String, String> constants = new TreeMap<>();
        for (Field field : type.getDeclaredFields()) {
            boolean isPublicStaticFinalString = field.getType() == String.class
                    && Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers());
            if (isPublicStaticFinalString) {
                constants.put(field.getName(), (String) field.get(null));
            }
        }
        return constants;
    }
}
