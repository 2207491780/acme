package com.acme.platform.workflow.api.dto;

import java.util.Map;

/**
 * 任务办结请求（POST /tasks/{taskId}/complete；form 落 wf_task.form_data jsonb）。
 * 办结人语义：assignee=me 或（未认领且候选含我，ALL 策略）——否则 WORKFLOW_ASSIGNEE_MISMATCH。
 */
public record TaskCompleteRequest(Map<String, Object> form) {
}
