package com.acme.platform.workflow.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 历史活动视图（GET /{id}/history；action=ENTER/LEAVE/REJECT_EDGE/TIMER_FIRE，按 start_at+id 序）。
 *
 * @param id       历史行 id
 * @param nodeId   节点 id
 * @param nodeType 节点 BPMN 类型名
 * @param action   动作（ENTER/LEAVE/REJECT_EDGE/TIMER_FIRE）
 * @param assignee 经办人（可空）
 * @param startAt  进入时刻
 * @param endAt    离开时刻（可空）
 * @param detail   附加明细（驳回边/定时器/委托记录等，可空）
 */
public record HistoryActivityView(Long id, String nodeId, String nodeType, String action, String assignee,
                                  Instant startAt, Instant endAt, Map<String, Object> detail) {

    /** 历史动作常量（wf_history_activity.action 语义域，AC-15 动作域钉死）。 */
    public static final String ACTION_ENTER = "ENTER";
    public static final String ACTION_LEAVE = "LEAVE";
    public static final String ACTION_REJECT_EDGE = "REJECT_EDGE";
    public static final String ACTION_TIMER_FIRE = "TIMER_FIRE";
}
