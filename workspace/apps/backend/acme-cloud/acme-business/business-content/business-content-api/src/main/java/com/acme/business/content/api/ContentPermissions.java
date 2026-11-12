package com.acme.business.content.api;

/**
 * 内容域权限点编码常量（架构设计 4.1 x-permission / 3.6 种子一一对应）：
 * 跨域权限目录平台级统一登记于 platform-system（M-03 数据归 system），本类为调用侧常量；
 * 内置超管经种子绑定全量权限点；demo_operator 不含 batch-delete（3.7 受限口径）。
 */
public final class ContentPermissions {

    /** 内容查询（列表 / 作者候选 / 分类清单）。 */
    public static final String CONTENT_LIST = "content:content:list";

    /** 标题行内修改。 */
    public static final String CONTENT_UPDATE = "content:content:update";

    /** 状态流转。 */
    public static final String CONTENT_TRANSITION = "content:content:transition";

    /** 批量下线。 */
    public static final String CONTENT_BATCH_OFFLINE = "content:content:batch-offline";

    /** 批量删除（软删）。 */
    public static final String CONTENT_BATCH_DELETE = "content:content:batch-delete";

    private ContentPermissions() {
    }
}
