package com.acme.platform.workflow.bpmn;

import java.util.Set;

/**
 * ACME 扩展属性名（acme:* 本地名；设计规格 §2 指派规则 + §7 委托配置终稿）。
 * retryPolicy 三参数以离散属性承载（acme:timeoutMs / acme:retryMaxAttempts / acme:retryBackoffMs），
 * 服务端缺省由 WorkflowProperties.Delegate 兜底——设计 §2/§7 的 acme:retryPolicy 复合属性以本
 * 三键展开实现（协议偏差报告钉终稿，见交付报告「委托协议终稿」）。
 */
public final class AcmeAttributes {

    /** 固定办理人（用户 id / ${initiator} / SpEL 表达式）。 */
    public static final String ASSIGNEE = "assignee";

    /** 候选角色编码列表（逗号分隔）。 */
    public static final String CANDIDATE_ROLES = "candidateRoles";

    /** 候选部门 id 列表（逗号分隔）。 */
    public static final String CANDIDATE_DEPTS = "candidateDepts";

    /** 候选用户 id 列表（逗号分隔）。 */
    public static final String CANDIDATE_USERS = "candidateUsers";

    /** 候选可见策略（一期仅 ALL——全部候选可见可办）。 */
    public static final String ASSIGNEE_STRATEGY = "assigneeStrategy";

    /** 委托目标服务名（Nacos lb 键；桩端点场景 http(s) 直连 URL）。 */
    public static final String SERVICE_REF = "serviceRef";

    /** 委托登记路径（目标服务承接下发的内部端点）。 */
    public static final String CALLBACK_PATH = "callbackPath";

    /** 下发超时毫秒。 */
    public static final String TIMEOUT_MS = "timeoutMs";

    /** 失败重试上限。 */
    public static final String RETRY_MAX_ATTEMPTS = "retryMaxAttempts";

    /** 重试首间隔毫秒。 */
    public static final String RETRY_BACKOFF_MS = "retryBackoffMs";

    /** 全量已知 acme 本地名（解析期未知扩展属性忽略不落模型）。 */
    public static final Set<String> KNOWN = Set.of(ASSIGNEE, CANDIDATE_ROLES, CANDIDATE_DEPTS,
            CANDIDATE_USERS, ASSIGNEE_STRATEGY, SERVICE_REF, CALLBACK_PATH, TIMEOUT_MS,
            RETRY_MAX_ATTEMPTS, RETRY_BACKOFF_MS);

    /** 策略缺省值（一期仅 ALL）。 */
    public static final String STRATEGY_ALL = "ALL";

    private AcmeAttributes() {
    }
}
