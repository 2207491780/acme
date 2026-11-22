package com.acme.platform.system.api;

/**
 * 平台权限点编码常量（FR-008、架构设计 5.2）：framework 侧不硬编码角色与权限点（Backend.md 8.2.1），
 * 编码仅存在于数据（种子脚本）与代码常量（本类）；内置超管角色经数据绑定全量权限点（等价短路）。
 * 清单与 openapi.json x-permission 标注一一对应；新增能力同步增补并登记种子脚本。
 */
public final class SystemPermissions {

    /** 凭据管理（认证域 Admin 端点，权限目录平台级统一登记）。 */
    public static final String AUTH_CREDENTIAL_MANAGE = "auth:credential:manage";

    /** 登录日志查询（认证域 Admin 端点；REQ-20261114-120105 登录安全域）。 */
    public static final String AUTH_LOGIN_LOG_LIST = "auth:loginlog:list";

    /** 在线用户查询（认证域 Admin 端点；REQ-20261114-120105 登录安全域）。 */
    public static final String AUTH_ONLINE_LIST = "auth:online:list";

    /** 在线用户踢下线（认证域 Admin 端点；REQ-20261114-120105 登录安全域）。 */
    public static final String AUTH_ONLINE_REVOKE = "auth:online:revoke";

    /** 用户管理。 */
    public static final String USER_LIST = "system:user:list";
    public static final String USER_CREATE = "system:user:create";
    public static final String USER_QUERY = "system:user:query";
    public static final String USER_UPDATE = "system:user:update";
    public static final String USER_DELETE = "system:user:delete";
    public static final String USER_ASSIGN_ROLE = "system:user:assign-role";

    /** 角色管理。 */
    public static final String ROLE_LIST = "system:role:list";
    public static final String ROLE_CREATE = "system:role:create";
    public static final String ROLE_QUERY = "system:role:query";
    public static final String ROLE_UPDATE = "system:role:update";
    public static final String ROLE_DELETE = "system:role:delete";
    public static final String ROLE_GRANT = "system:role:grant";

    /** 角色数据范围（REQ-20261114-120104：sys_role.data_scope 五档 + CUSTOM 部门集）。 */
    public static final String ROLE_SCOPE = "system:role:scope";

    /** 部门管理（REQ-20261114-120104 组织架构）。 */
    public static final String DEPT_LIST = "system:dept:list";
    public static final String DEPT_CREATE = "system:dept:create";
    public static final String DEPT_UPDATE = "system:dept:update";
    public static final String DEPT_DELETE = "system:dept:delete";

    /** 岗位管理（REQ-20261114-120104 组织架构）。 */
    public static final String POSITION_LIST = "system:position:list";
    public static final String POSITION_CREATE = "system:position:create";
    public static final String POSITION_UPDATE = "system:position:update";
    public static final String POSITION_DELETE = "system:position:delete";

    /** 权限点管理。 */
    public static final String PERMISSION_LIST = "system:permission:list";
    public static final String PERMISSION_CREATE = "system:permission:create";
    public static final String PERMISSION_QUERY = "system:permission:query";
    public static final String PERMISSION_UPDATE = "system:permission:update";
    public static final String PERMISSION_DELETE = "system:permission:delete";

    /** 菜单管理。 */
    public static final String MENU_LIST = "system:menu:list";
    public static final String MENU_CREATE = "system:menu:create";
    public static final String MENU_QUERY = "system:menu:query";
    public static final String MENU_UPDATE = "system:menu:update";
    public static final String MENU_DELETE = "system:menu:delete";

    /** 字典管理。 */
    public static final String DICT_LIST = "system:dict:list";
    public static final String DICT_CREATE = "system:dict:create";
    public static final String DICT_UPDATE = "system:dict:update";
    public static final String DICT_DELETE = "system:dict:delete";

    /** 租户管理。 */
    public static final String TENANT_LIST = "system:tenant:list";
    public static final String TENANT_CREATE = "system:tenant:create";
    public static final String TENANT_QUERY = "system:tenant:query";
    public static final String TENANT_UPDATE = "system:tenant:update";

    /**
     * 租户开通初始化模板（REQ-20261122-233426 确认⑤；platform-system V32 种子，sys_permission
     * 83~86——纯 API 权限点无菜单节点，挂靠租户管理菜单 id=7；保留域成员，不授予租户管理员）。
     */
    public static final String TENANT_TEMPLATE_LIST = "system:tenant-template:list";
    public static final String TENANT_TEMPLATE_CREATE = "system:tenant-template:create";
    public static final String TENANT_TEMPLATE_UPDATE = "system:tenant-template:update";
    public static final String TENANT_TEMPLATE_DELETE = "system:tenant-template:delete";

    /** 平台运营总览（REQ-20261122-233426 确认⑥；V32 种子 87——跨租户实时聚合旁路面）。 */
    public static final String TENANT_USAGE_OVERVIEW = "system:tenant:usage-overview";

    /** 审计事件查询（infra 域 Admin 端点，权限目录平台级统一登记；platform-infra-audit 增补）。 */
    public static final String INFRA_AUDIT_LIST = "infra:audit:list";

    /** 全租户审计视图（数据权限：无此权限点仅本人视图；consumer-auth-refresh-dataperm）。 */
    public static final String INFRA_AUDIT_LIST_ALL = "infra:audit:list:all";

    /** 文件查询（infra 域 Admin 端点；REQ-20261114-071607 文件服务，含元数据读取）。 */
    public static final String INFRA_FILE_LIST = "infra:file:list";

    /** 文件上传（infra 域 Admin 端点；REQ-20261114-071607 文件服务）。 */
    public static final String INFRA_FILE_UPLOAD = "infra:file:upload";

    /** 文件下载（infra 域 Admin 端点；REQ-20261114-071607 文件服务）。 */
    public static final String INFRA_FILE_DOWNLOAD = "infra:file:download";

    /** 文件删除（infra 域 Admin 端点；REQ-20261114-071607 文件服务）。 */
    public static final String INFRA_FILE_DELETE = "infra:file:delete";

    /** 任务注册表查询（infra 域 Admin 端点；REQ-20261114-071607 任务调度）。 */
    public static final String INFRA_JOB_LIST = "infra:job:list";

    /** 任务手动触发（infra 域 Admin 端点；REQ-20261114-071607 任务调度）。 */
    public static final String INFRA_JOB_RUN = "infra:job:run";

    /** 异常日志查询（infra 域 Admin 端点；REQ-20261117-061017 日志中心完善）。 */
    public static final String INFRA_EXCEPTION_LIST = "infra:exception:list";

    /** Excel 通用范式 demo（infra 域 Admin 端点；REQ-20261114-071607：单点覆盖模板/导出/导入三端点）。 */
    public static final String INFRA_EXCEL_DEMO = "infra:excel:demo";

    /** 通知公告（REQ-20261114-120102 消息通知闭环一期；详情 GET /{id} 归 list 读语义，V7 先例）。 */
    public static final String NOTICE_LIST = "system:notice:list";
    public static final String NOTICE_CREATE = "system:notice:create";
    public static final String NOTICE_UPDATE = "system:notice:update";
    public static final String NOTICE_DELETE = "system:notice:delete";
    public static final String NOTICE_PUBLISH = "system:notice:publish";

    /** 站内信（REQ-20261114-120102；收件箱 / 未读数归 list 读语义，标记已读独立权限点）。 */
    public static final String MESSAGE_SEND = "system:message:send";
    public static final String MESSAGE_LIST = "system:message:list";
    public static final String MESSAGE_READ = "system:message:read";

    /** 租户级系统配置（REQ-20261116-230354：GET 认证即可读，list 点供菜单显隐；PUT 走 update 点）。 */
    public static final String CONFIG_LIST = "system:config:list";
    public static final String CONFIG_UPDATE = "system:config:update";

    /** 租户套餐（REQ-20261121-022910；详情 GET /{id} 归 list 读语义，通知公告/部门先例）。 */
    public static final String TENANT_PACKAGE_LIST = "system:tenant-package:list";
    public static final String TENANT_PACKAGE_CREATE = "system:tenant-package:create";
    public static final String TENANT_PACKAGE_UPDATE = "system:tenant-package:update";
    public static final String TENANT_PACKAGE_DELETE = "system:tenant-package:delete";

    /**
     * 调度中心（REQ-20261121-071002 确认②；platform-system V26 种子，sys_permission 73~77）。
     * executions 列表归 execution:list（77）、executors 列表归 job:list（73）——契约 frontmatter 语义。
     */
    public static final String SCHEDULER_JOB_LIST = "scheduler:job:list";
    public static final String SCHEDULER_JOB_CREATE = "scheduler:job:create";
    public static final String SCHEDULER_JOB_UPDATE = "scheduler:job:update";
    public static final String SCHEDULER_JOB_DELETE = "scheduler:job:delete";
    public static final String SCHEDULER_EXECUTION_LIST = "scheduler:execution:list";

    /**
     * BPM 工作流引擎（REQ-20261121-142316 确认⑦；platform-system V27 种子，sys_permission 78~82）：
     * definition:deploy 覆盖 PUT /{id}/xml、POST /{id}/deploy、POST /{id}/disable 三写端点
     * （服务侧 RequirePermission 三端点统一校验该点）；instance:manage 含 history 查询。
     */
    public static final String WORKFLOW_DEFINITION_LIST = "workflow:definition:list";
    public static final String WORKFLOW_DEFINITION_DEPLOY = "workflow:definition:deploy";
    public static final String WORKFLOW_INSTANCE_MANAGE = "workflow:instance:manage";
    public static final String WORKFLOW_TASK_LIST = "workflow:task:list";
    public static final String WORKFLOW_TASK_OPERATE = "workflow:task:operate";

    private SystemPermissions() {
    }
}
