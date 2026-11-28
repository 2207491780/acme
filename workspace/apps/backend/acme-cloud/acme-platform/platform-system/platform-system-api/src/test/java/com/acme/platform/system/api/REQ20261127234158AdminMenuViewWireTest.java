package com.acme.platform.system.api;

import com.acme.common.serialization.JsonModules;
import com.acme.platform.system.api.dto.AdminMenuView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-20261127-234158（P2-02）：AdminMenuView wire 契约测试（真实 JsonModules.standard() mapper
 * 断言 writeValueAsString/readValue 产物，SystemApiContractTest 同包先例口径，非 MSW）。
 * 锁定：id/parentId/version 均 Long→JSON 字符串、根节点 parentId="0"（字符串化零）、
 * children 嵌套保留、MenuType/EnableStatus 枚举按名、sortOrder（Integer）/visible（Boolean）
 * 保持数值/布尔口径。
 */
class REQ20261127234158AdminMenuViewWireTest {

    private final JsonMapper mapper = buildMapper();

    private static JsonMapper buildMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        for (JacksonModule module : JsonModules.standard()) {
            builder.addModule(module);
        }
        return builder.build();
    }

    @Test
    void adminMenuViewTreeSerializesLongIdsAsStringsAndKeepsChildrenNesting() {
        // 叶节点 children 传 null（紧凑构造器归一 List.of()，wire 为空数组）
        AdminMenuView child = new AdminMenuView(11L, 1L, "用户管理", MenuType.MENU,
                "/system/users", "system/user/index", "user", 2, false, "system:user:list",
                EnableStatus.ENABLED, null, 5L);
        AdminMenuView root = new AdminMenuView(1L, 0L, "系统管理", MenuType.DIRECTORY,
                "/system", null, "setting", 1, true, null,
                EnableStatus.ENABLED, List.of(child), 3L);
        String json = mapper.writeValueAsString(root);
        // id/parentId/version Long→JSON 字符串（AC-8 golden；根 parentId="0" 为字符串化零，非 null/数值）
        assertThat(json).contains("\"id\":\"1\"");
        assertThat(json).contains("\"parentId\":\"0\"");
        assertThat(json).contains("\"id\":\"11\"");
        assertThat(json).contains("\"parentId\":\"1\"");
        assertThat(json).contains("\"version\":\"3\"");
        assertThat(json).contains("\"version\":\"5\"");
        // 枚举按名（openapi MenuType / EnableStatus）
        assertThat(json).contains("\"menuType\":\"DIRECTORY\"");
        assertThat(json).contains("\"menuType\":\"MENU\"");
        assertThat(json).contains("\"status\":\"ENABLED\"");
        // Integer 保持数值口径、Boolean 保持布尔口径（不在 Long→String 化范围）
        assertThat(json).contains("\"sortOrder\":1");
        assertThat(json).contains("\"visible\":true");
        // children 嵌套保留：根含子节点对象，叶节点 children 为空数组
        assertThat(json).contains("\"children\":[{");
        assertThat(json).contains("\"children\":[]");
        // 回读：字符串化 id/parentId 双口径还原 Long 值，树结构与 children 归一保持
        AdminMenuView back = mapper.readValue(json, AdminMenuView.class);
        assertThat(back).isEqualTo(root);
        assertThat(back.id()).isEqualTo(1L);
        assertThat(back.parentId()).isEqualTo(0L);
        assertThat(back.version()).isEqualTo(3L);
        assertThat(back.children()).hasSize(1);
        assertThat(back.children().get(0).id()).isEqualTo(11L);
        assertThat(back.children().get(0).parentId()).isEqualTo(1L);
        assertThat(back.children().get(0).menuType()).isEqualTo(MenuType.MENU);
        assertThat(back.children().get(0).children()).isEmpty();
    }
}
