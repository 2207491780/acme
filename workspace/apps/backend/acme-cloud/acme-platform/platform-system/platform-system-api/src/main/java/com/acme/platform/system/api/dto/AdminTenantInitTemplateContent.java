package com.acme.platform.system.api.dto;

import java.util.List;

/**
 * 开通模板内容（REQ-20261122-233426 确认②形状；快照语义——开通时按本结构建角色集/绑授权/
 * 写配置/默认套餐，非引用绑定）：
 * <ul>
 *   <li>adminUsernamePrefix 可选：请求 adminUsername 缺席时初始管理员用户名=前缀+租户编码
 *       （请求显式值优先，确认③「adminUsername 优先模板前缀」）；</li>
 *   <li>adminNickname 可选：请求 adminNickname 缺席时初始管理员昵称兜底；</li>
 *   <li>rolePresets 必填非空：模板显式角色集（绕开「动态全量授权集」，确认③跳过动态授权集计算）
 *       ——roleKey=租户内角色编码（预设间禁重复），roleName=角色名，permissionCodes=该角色授权的
 *       权限点编码全集（仅允许非保留前缀，落库时校验）；</li>
 *   <li>configEntries 可选：开通写入 sys_config（租户域）的配置项——configKey 非空白不重复即可
 *       入库（快照原样保存，键词汇契约未钉死）；已知键=系统配置六键（watermarkEnabled/
 *       captchaEnabled/lockIdleTimeoutMs/footerCopyright/footerVisible/watermarkTemplate，与
 *       AdminSystemConfigUpdate 字段同词汇）开通时按值写入新租户 sys_config 行（value=字符串形态，
 *       布尔/整型键由保存校验解析合法性）；未知键开通时 WARN 跳过（固定列 schema 无处可写）。</li>
 * </ul>
 *
 * <p>校验口径：content 内部合法性（空角色集/保留前缀/重复 roleKey/空白键/已知键不可解析值）
 * 统一由 TenantInitTemplateUseCase 落库时校验并抛 TENANT_TEMPLATE_CONTENT_INVALID（400
 * PARAM_INVALID 口径）——本 record 不挂 bean 约束，避免双重校验口径分裂（结构错误统一业务码承载）。</p>
 */
public record AdminTenantInitTemplateContent(String adminUsernamePrefix,
                                             String adminNickname,
                                             List<RolePreset> rolePresets,
                                             List<ConfigEntry> configEntries) {

    public AdminTenantInitTemplateContent {
        rolePresets = rolePresets == null ? List.of() : List.copyOf(rolePresets);
        configEntries = configEntries == null ? List.of() : List.copyOf(configEntries);
    }

    /**
     * 模板角色预设：开通时按预设创建租户内角色并绑定 permissionCodes（快照授权集）。
     *
     * @param roleKey         租户内角色编码（同租户唯一，预设间重复保存即拒）
     * @param roleName        角色名称
     * @param permissionCodes 授权权限点编码集（禁保留前缀；去重后绑定）
     */
    public record RolePreset(String roleKey,
                             String roleName,
                             List<String> permissionCodes) {

        public RolePreset {
            permissionCodes = permissionCodes == null ? List.of() : List.copyOf(permissionCodes);
        }
    }

    /**
     * 模板配置项：开通时写入新租户 sys_config 行（configKey 六键值域见类注；value 统一字符串形态）。
     *
     * @param configKey 配置键（系统配置六键之一，未知键保存即拒——固定列 schema 无处可写）
     * @param value     配置值（布尔/整型键须可解析，保存校验；字符串键 null=清除）
     */
    public record ConfigEntry(String configKey,
                              String value) {
    }
}
