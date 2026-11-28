package com.acme.platform.scheduler.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261128-051442（AC-11）：SchedulerErrorCodes 全量常量快照测试（非 MSW）。
 * 契约附录 A.6 为唯一权威清单：反射枚举全部 public static final String 常量（按名排序），
 * 断言 (name→value) 全集精确等于附录 A scheduler 切片——双向锁：缺常量、多常量、任一值漂移皆红。
 * 决策①：后端 Result.code() 与一切错误码定义零改动，本测试仅锁定现状 wire 真值。
 */
class REQ20261128051442SchedulerErrorCodesSnapshotTest {

    /** 附录 A.6 切片（platform-scheduler 段，wire 值 ← 常量名，7 码）。 */
    private static final Map<String, String> CONTRACT_APPENDIX_A_SCHEDULER = Map.ofEntries(
            // ---- 01 任务定义 ----
            Map.entry("SCHEDULER_JOB_KEY_DUPLICATE", "platform-scheduler-01-0001"),
            Map.entry("SCHEDULER_JOB_NOT_FOUND", "platform-scheduler-01-0002"),
            Map.entry("SCHEDULER_JOB_KEY_IMMUTABLE", "platform-scheduler-01-0003"),
            Map.entry("SCHEDULER_TRIGGER_INVALID", "platform-scheduler-01-0004"),
            Map.entry("SCHEDULER_RETRY_POLICY_INVALID", "platform-scheduler-01-0005"),
            // ---- 02 执行历史 ----
            Map.entry("SCHEDULER_EXECUTION_NOT_FOUND", "platform-scheduler-02-0001"),
            Map.entry("SCHEDULER_EXECUTION_STATE_CONFLICT", "platform-scheduler-02-0002"));

    @Test
    void schedulerErrorCodesConstantsExactlyMatchContractAppendixA() throws IllegalAccessException {
        assertThat(publicStringConstants(SchedulerErrorCodes.class))
                .containsExactlyInAnyOrderEntriesOf(CONTRACT_APPENDIX_A_SCHEDULER);
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
