package com.acme.platform.workflow.engine;

import com.acme.platform.workflow.domain.WfVariable;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * 流程变量作用域视图（子流程遮蔽实现位——设计规格 §2/§3.2：读穿透、写不穿透）。
 * 根作用域变量名原样落 wf_variable.name；子流程 scope 以组合键 {@code wf:scope:{scopeRoot}:{name}}
 * 承载（契约确认② instance 级单表 uk(instance_id,name) 约束下的遮蔽实现位）。
 * <p>快照（snapshot）与写覆盖（overlay）由 EngineRuntime 持有共享（同实例同命令全视图共用）；
 * 视图仅携带 scope 链（BPM-1 round-2 D1/D2：链按 execution 树派生——is_scope_root 行值权威，
 * 跨命令/并行分支各自派生，不再使用 runtime 级游标）。</p>
 * 读取链：命令内写覆盖 → 当前 scope 组合键 → 父 scope 逐级 → 根名；写入只落当前 scope。
 */
public final class VariableScope {

    /** scope 组合键前缀（子流程 scope 段）。 */
    public static final String SCOPE_PREFIX = "wf:scope:";

    /** 根作用域段名。 */
    public static final String ROOT_SEGMENT = "root";

    private final Long instanceId;
    private final JsonMapper jsonMapper;
    private final PersistenceSink sink;
    private final Map<String, WfVariable> snapshot;
    private final Map<String, Map<String, Object>> overlay;
    private final List<String> chain;

    public VariableScope(Long instanceId, JsonMapper jsonMapper, PersistenceSink sink,
                         Map<String, WfVariable> snapshot,
                         Map<String, Map<String, Object>> overlay, List<String> chain) {
        this.instanceId = instanceId;
        this.jsonMapper = jsonMapper;
        this.sink = sink;
        this.snapshot = snapshot;
        this.overlay = overlay;
        this.chain = List.copyOf(chain);
    }

    /** 持久化汇（CommandContext 承载：saveVariable/deleteVariablesByPrefix 落命令事务队列；
     *  实例 id 由汇内惰性解析——落库后非空）。 */
    public interface PersistenceSink {

        void saveVariable(String name, Object value);

        void deleteVariablesByPrefix(String prefix);
    }

    /** 变量读取（读穿透：当前 scope → 父 scope 逐级 → 根）。 */
    public Object get(String name) {
        for (String scope : chain) {
            Map<String, Object> scopeOverlay = overlay.get(scope);
            if (scopeOverlay != null && scopeOverlay.containsKey(name)) {
                return scopeOverlay.get(name);
            }
            WfVariable row = snapshot.get(composeName(scope, name));
            if (row != null) {
                return decode(row.getValueJson());
            }
        }
        return null;
    }

    /** 变量写入（写不穿透：只落当前 scope——子流程内写入随 scope 回收，父流程不可见）。 */
    public void set(String name, Object value) {
        String scope = chain.get(0);
        overlay.computeIfAbsent(scope, k -> new java.util.LinkedHashMap<>()).put(name, value);
        sink.saveVariable(composeName(scope, name), value);
    }

    /** 根作用域变量摘要（实例详情/委托快照/表达式求值面：仅根名行）。 */
    public Map<String, Object> rootSummary() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : overlay
                .getOrDefault(ROOT_SEGMENT, Map.of()).entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        for (WfVariable row : snapshot.values()) {
            if (!row.getName().startsWith(SCOPE_PREFIX) && !result.containsKey(row.getName())) {
                result.put(row.getName(), decode(row.getValueJson()));
            }
        }
        return result;
    }

    /** scope 组合名（root 段为裸名）。 */
    public static String composeName(String scope, String name) {
        return ROOT_SEGMENT.equals(scope) ? name : SCOPE_PREFIX + scope + ":" + name;
    }

    /** 链首段（最近 scopeRoot 段名或 root）——EngineRuntime 回收面取用。 */
    public String chainFirst() {
        return chain.get(0);
    }

    /** JSON 解码（null/空=Null）。 */
    private Object decode(String valueJson) {
        if (valueJson == null || valueJson.isEmpty()) {
            return null;
        }
        return jsonMapper.readValue(valueJson, Object.class);
    }
}
