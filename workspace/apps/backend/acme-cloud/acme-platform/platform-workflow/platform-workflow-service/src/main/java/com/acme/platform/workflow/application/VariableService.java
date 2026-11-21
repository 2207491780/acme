package com.acme.platform.workflow.application;

import com.acme.platform.workflow.domain.WfVariable;
import com.acme.platform.workflow.infrastructure.repository.WfVariableRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 流程变量服务（upsert/回收；uk(instance_id,name) 冲突转乐观锁更新——value jsonb 经 JsonMapper）。
 */
@Service
public class VariableService {

    private final WfVariableRepository variableRepository;
    private final JsonMapper jsonMapper;

    public VariableService(WfVariableRepository variableRepository, JsonMapper jsonMapper) {
        this.variableRepository = variableRepository;
        this.jsonMapper = jsonMapper;
    }

    /** upsert（存在即更新，不存在插入；新实体租户列由插入方显式携带）。 */
    public void upsert(Long instanceId, String name, Object value, String tenantId) {
        String valueJson = value == null ? "null" : jsonMapper.writeValueAsString(value);
        WfVariable existing = variableRepository.findByInstanceIdAndName(instanceId, name).orElse(null);
        if (existing != null) {
            existing.setValueJson(valueJson);
            variableRepository.save(existing);
            return;
        }
        WfVariable variable = new WfVariable();
        variable.setTenantId(tenantId);
        variable.setInstanceId(instanceId);
        variable.setName(name);
        variable.setValueJson(valueJson);
        variableRepository.save(variable);
    }

    /** scope 前缀回收（子流程结束：物理删除——FlexDeletes 旁路口径）。 */
    public void deleteByPrefix(Long instanceId, String prefix) {
        List<WfVariable> doomed = variableRepository.findByInstanceIdAndNamePrefix(instanceId, prefix);
        if (doomed.isEmpty()) {
            return;
        }
        variableRepository.forceDeleteByQuery(QueryWrapper.create()
                .where(WfVariable::getInstanceId).eq(instanceId)
                .and(WfVariable::getName).in(doomed.stream().map(WfVariable::getName).toList()));
    }

    /** 快照装载（作用域视图输入面）。 */
    public java.util.Map<String, WfVariable> snapshot(Long instanceId) {
        java.util.Map<String, WfVariable> result = new java.util.LinkedHashMap<>();
        for (WfVariable variable : variableRepository.findByInstanceId(instanceId)) {
            result.put(variable.getName(), variable);
        }
        return result;
    }
}
