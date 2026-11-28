package com.acme.platform.infra.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261128-051442（AC-11）：InfraErrorCodes 全量常量快照测试（非 MSW）。
 * 契约附录 A.7 为唯一权威清单：反射枚举全部 public static final String 常量（按名排序），
 * 断言 (name→value) 全集精确等于附录 A infra 切片——双向锁：缺常量、多常量、任一值漂移皆红。
 * InfraErrorCodes 域内分裂如实锁定双口径：2 个三段全码（platform-infra-01-0001/0002）
 * + 12 个裸字面量（wire 值 = 常量名字面量，如 FILE_SIZE_EXCEEDED）。
 * 决策①：后端 Result.code() 与一切错误码定义零改动，本测试仅锁定现状 wire 真值。
 */
class REQ20261128051442InfraErrorCodesSnapshotTest {

    /** 附录 A.7 切片（platform-infra 段，wire 值 ← 常量名，14 码：2 三段全码 + 12 裸字面量）。 */
    private static final Map<String, String> CONTRACT_APPENDIX_A_INFRA = Map.ofEntries(
            // ---- 三段全码段（01 审计）----
            Map.entry("AUDIT_EVENT_INVALID", "platform-infra-01-0001"),
            Map.entry("AUDIT_STORE_FAILED", "platform-infra-01-0002"),
            // ---- 裸字面量段（wire 值 = 常量名字面量）----
            Map.entry("FILE_SIZE_EXCEEDED", "FILE_SIZE_EXCEEDED"),
            Map.entry("FILE_TYPE_REJECTED", "FILE_TYPE_REJECTED"),
            Map.entry("FILE_NOT_FOUND", "FILE_NOT_FOUND"),
            Map.entry("EXCEL_PARSE_FAILED", "EXCEL_PARSE_FAILED"),
            Map.entry("JOB_ALREADY_RUNNING", "JOB_ALREADY_RUNNING"),
            Map.entry("JOB_NOT_FOUND", "JOB_NOT_FOUND"),
            Map.entry("STORAGE_QUOTA_EXCEEDED", "STORAGE_QUOTA_EXCEEDED"),
            Map.entry("IMAGE_INVALID", "IMAGE_INVALID"),
            Map.entry("TICKET_INVALID", "TICKET_INVALID"),
            Map.entry("SIZE_MISMATCH", "SIZE_MISMATCH"),
            Map.entry("STORAGE_OBJECT_MISSING", "STORAGE_OBJECT_MISSING"),
            Map.entry("OP_EXCEPTION_NOT_FOUND", "OP_EXCEPTION_NOT_FOUND"));

    @Test
    void infraErrorCodesConstantsExactlyMatchContractAppendixA() throws IllegalAccessException {
        assertThat(publicStringConstants(InfraErrorCodes.class))
                .containsExactlyInAnyOrderEntriesOf(CONTRACT_APPENDIX_A_INFRA);
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
