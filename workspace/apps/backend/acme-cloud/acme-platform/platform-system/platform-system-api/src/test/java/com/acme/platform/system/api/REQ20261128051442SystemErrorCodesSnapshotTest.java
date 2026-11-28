package com.acme.platform.system.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261128-051442（AC-11）：SystemErrorCodes 全量常量快照测试（非 MSW）。
 * 契约附录 A.3 为唯一权威清单：反射枚举全部 public static final String 常量（按名排序），
 * 断言 (name→value) 全集精确等于附录 A system 切片——双向锁：缺常量、多常量、任一值漂移皆红。
 * 决策①：后端 Result.code() 与一切错误码定义零改动，本测试仅锁定现状 wire 真值。
 */
class REQ20261128051442SystemErrorCodesSnapshotTest {

    /** 附录 A.3 切片（platform-system 段，wire 值 ← 常量名，49 码）。 */
    private static final Map<String, String> CONTRACT_APPENDIX_A_SYSTEM = Map.ofEntries(
            // ---- 01 用户 ----
            Map.entry("USER_NOT_FOUND", "platform-system-01-0001"),
            Map.entry("USERNAME_DUPLICATED", "platform-system-01-0002"),
            Map.entry("USER_DELETE_DENIED", "platform-system-01-0003"),
            // ---- 02 角色 ----
            Map.entry("ROLE_NOT_FOUND", "platform-system-02-0001"),
            Map.entry("ROLE_CODE_DUPLICATED", "platform-system-02-0002"),
            Map.entry("ROLE_DELETE_DENIED", "platform-system-02-0003"),
            Map.entry("ROLE_PARENT_CYCLE", "platform-system-02-0004"),
            Map.entry("ROLE_REPARENT_DENIED", "platform-system-02-0005"),
            Map.entry("ROLE_CODE_RESERVED", "platform-system-02-0006"),
            Map.entry("ROLE_GRANT_RESERVED", "platform-system-02-0007"),
            Map.entry("DATA_SCOPE_INVALID", "platform-system-02-0008"),
            // ---- 03 权限 ----
            Map.entry("PERMISSION_NOT_FOUND", "platform-system-03-0001"),
            Map.entry("PERMISSION_CODE_DUPLICATED", "platform-system-03-0002"),
            Map.entry("PERMISSION_DELETE_DENIED", "platform-system-03-0003"),
            // ---- 04 菜单 ----
            Map.entry("MENU_NOT_FOUND", "platform-system-04-0001"),
            Map.entry("MENU_DELETE_DENIED", "platform-system-04-0002"),
            // ---- 05 字典 ----
            Map.entry("DICT_TYPE_NOT_FOUND", "platform-system-05-0001"),
            Map.entry("DICT_CODE_DUPLICATED", "platform-system-05-0002"),
            Map.entry("DICT_ITEM_DUPLICATED", "platform-system-05-0003"),
            Map.entry("DICT_TYPE_DELETE_DENIED", "platform-system-05-0004"),
            // ---- 06 租户 ----
            Map.entry("TENANT_NOT_FOUND", "platform-system-06-0001"),
            Map.entry("TENANT_CODE_DUPLICATED", "platform-system-06-0002"),
            Map.entry("TENANT_DISABLE_DENIED", "platform-system-06-0003"),
            Map.entry("TENANT_DEPTH_EXCEEDED", "platform-system-06-0004"),
            Map.entry("TENANT_CYCLE", "platform-system-06-0005"),
            // ---- 07 鉴权 ----
            Map.entry("PERMISSION_DENIED", "platform-system-07-0001"),
            // ---- 08 公告 ----
            Map.entry("NOTICE_PUBLISHED", "platform-system-08-0001"),
            Map.entry("NOTICE_NOT_FOUND", "platform-system-08-0002"),
            // ---- 09 站内信 ----
            Map.entry("MESSAGE_NOT_FOUND", "platform-system-09-0001"),
            Map.entry("MESSAGE_FORBIDDEN", "platform-system-09-0002"),
            // ---- 10 部门 ----
            Map.entry("DEPT_NOT_FOUND", "platform-system-10-0001"),
            Map.entry("DEPT_HAS_CHILDREN", "platform-system-10-0002"),
            Map.entry("DEPT_HAS_USERS", "platform-system-10-0003"),
            Map.entry("DEPT_PARENT_CYCLE", "platform-system-10-0004"),
            // ---- 11 岗位 ----
            Map.entry("POSITION_NOT_FOUND", "platform-system-11-0001"),
            Map.entry("POSITION_IN_USE", "platform-system-11-0002"),
            Map.entry("POSITION_CODE_DUPLICATED", "platform-system-11-0003"),
            // ---- 12 系统配置 ----
            Map.entry("SYSTEM_CONFIG_INVALID", "platform-system-12-0001"),
            Map.entry("SYSTEM_WATERMARK_TEMPLATE_INVALID", "platform-system-12-0002"),
            // ---- 13 资料 ----
            Map.entry("PROFILE_INVALID", "platform-system-13-0001"),
            // ---- 14 租户套餐 ----
            Map.entry("TENANT_PACKAGE_DUPLICATE", "platform-system-14-0001"),
            Map.entry("TENANT_PACKAGE_NOT_FOUND", "platform-system-14-0002"),
            Map.entry("TENANT_PACKAGE_IN_USE", "platform-system-14-0003"),
            Map.entry("TENANT_PACKAGE_MENU_INVALID", "platform-system-14-0004"),
            Map.entry("TENANT_PACKAGE_MENU_FORBIDDEN", "platform-system-14-0005"),
            Map.entry("TENANT_CAPABILITY_INVALID", "platform-system-14-0006"),
            // ---- 15 租户模板 ----
            Map.entry("TENANT_TEMPLATE_NOT_FOUND", "platform-system-15-0001"),
            Map.entry("TENANT_TEMPLATE_DUPLICATE", "platform-system-15-0002"),
            Map.entry("TENANT_TEMPLATE_CONTENT_INVALID", "platform-system-15-0003"));

    @Test
    void systemErrorCodesConstantsExactlyMatchContractAppendixA() throws IllegalAccessException {
        assertThat(publicStringConstants(SystemErrorCodes.class))
                .containsExactlyInAnyOrderEntriesOf(CONTRACT_APPENDIX_A_SYSTEM);
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
