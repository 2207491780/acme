package com.acme.platform.workflow.engine;

import com.acme.platform.workflow.domain.WfHistoryActivity;
import com.acme.platform.workflow.infrastructure.repository.WfHistoryActivityRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 历史记录器（AC-15：ENTER/LEAVE/REJECT_EDGE/TIMER_FIRE 按序完整；同命令事务内落库，
 * AFTER_COMMIT 事件异步面另行承载——历史行不丢）。
 */
@Component
public class HistoryRecorder {

    private final WfHistoryActivityRepository historyRepository;

    public HistoryRecorder(WfHistoryActivityRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    /** 记录动作（detail 可空；instance_id 兜底回填——修复⑧同面；持久化直写）。 */
    public void record(EngineRuntime runtime, String nodeId, String nodeType, String action,
                       String assignee, Map<String, Object> detail) {
        WfHistoryActivity history = runtime.newHistory(nodeId, nodeType, action, assignee, detail);
        runtime.persist(() -> {
            if (history.getInstanceId() == null) {
                history.setInstanceId(runtime.instance().getId());
            }
            if (history.getTenantId() == null) {
                history.setTenantId(runtime.tenantId());
            }
            historyRepository.save(history);
        });
    }

    /** ENTER（token 到达，start_at=now）。 */
    public void enter(EngineRuntime runtime, String nodeId, String nodeType, String assignee) {
        record(runtime, nodeId, nodeType,
                com.acme.platform.workflow.api.dto.HistoryActivityView.ACTION_ENTER, assignee, null);
    }

    /** LEAVE（token 离开，补 end_at）。 */
    public void leave(EngineRuntime runtime, String nodeId, String nodeType, String assignee,
                      Map<String, Object> detail) {
        WfHistoryActivity history = runtime.newHistory(nodeId, nodeType,
                com.acme.platform.workflow.api.dto.HistoryActivityView.ACTION_LEAVE, assignee, detail);
        history.setEndAt(runtime.now());
        runtime.persist(() -> {
            if (history.getInstanceId() == null) {
                history.setInstanceId(runtime.instance().getId());
            }
            if (history.getTenantId() == null) {
                history.setTenantId(runtime.tenantId());
            }
            historyRepository.save(history);
        });
    }

    /** TIMER_FIRE（定时中断，AC-15 动作域）。 */
    public void timerFire(EngineRuntime runtime, String boundaryEventId, Map<String, Object> detail) {
        record(runtime, boundaryEventId, "BOUNDARY_TIMER",
                com.acme.platform.workflow.api.dto.HistoryActivityView.ACTION_TIMER_FIRE, null, detail);
    }

    /** REJECT_EDGE（驳回边，确认⑤：历史链保留）。 */
    public void rejectEdge(EngineRuntime runtime, String targetNodeId, Map<String, Object> detail) {
        record(runtime, targetNodeId, "USER_TASK",
                com.acme.platform.workflow.api.dto.HistoryActivityView.ACTION_REJECT_EDGE, null, detail);
    }
}
