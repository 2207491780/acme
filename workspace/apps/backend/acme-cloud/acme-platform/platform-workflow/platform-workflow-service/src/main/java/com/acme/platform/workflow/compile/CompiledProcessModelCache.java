package com.acme.platform.workflow.compile;

import com.acme.platform.workflow.api.WorkflowDefinitionStatus;
import com.acme.platform.workflow.domain.WfProcessDefinition;
import com.acme.platform.workflow.bpmn.BpmnDocumentParser;
import com.acme.platform.workflow.infrastructure.repository.WfProcessDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 编译模型缓存（ConcurrentHashMap&lt;definitionId&gt;，设计规格 §3.4）：首次访问加载 + 缓存、
 * DISABLED/新版本部署即失效、运行时零 XML 解析；运行期查询不触发解析（AC-13）。
 * 失效时机：deploy（同 key 新版本→缓存新模型）、disable（移除）。
 */
@Component
public class CompiledProcessModelCache {

    private static final Logger log = LoggerFactory.getLogger(CompiledProcessModelCache.class);

    private final WfProcessDefinitionRepository definitionRepository;
    private final ConcurrentHashMap<Long, CompiledProcessModel> cache = new ConcurrentHashMap<>();

    public CompiledProcessModelCache(WfProcessDefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    /**
     * 取编译模型（BPM-1 round-2 D3：缓存 miss 时按库内定义重载编译——DEPLOYED 入缓存复用；
     * DISABLED 的定义只要落库有 bpmn_xml 即「临时编译不缓存」，保障「disable 运行实例不受影响」
     * （运行链任务操作按落库定义重编译继续）；DRAFT（无 XML）→ 02-0003 由启动守卫承载。
     */
    public CompiledProcessModel get(Long definitionId) {
        CompiledProcessModel model = cache.get(definitionId);
        if (model != null) {
            return model;
        }
        WfProcessDefinition definition = definitionRepository.findById(definitionId)
                .orElseThrow(() -> new IllegalArgumentException("流程定义不存在：" + definitionId));
        if (definition.getStatus() != WorkflowDefinitionStatus.DEPLOYED
                && definition.getStatus() != WorkflowDefinitionStatus.DISABLED) {
            throw new IllegalStateException("流程定义未部署：" + definitionId);
        }
        CompiledProcessModel compiled = compile(definition);
        if (definition.getStatus() == WorkflowDefinitionStatus.DEPLOYED) {
            cache.put(definitionId, compiled);
        }
        return compiled;
    }

    /** 显式装载（部署命令内调用：编译失败→校验错误上抛，DEPLOYED 状态落库前编译先行）。 */
    public CompiledProcessModel load(WfProcessDefinition definition) {
        CompiledProcessModel compiled = compile(definition);
        cache.put(definition.getId(), compiled);
        return compiled;
    }

    /** 失效（disable / 新版本部署前旧条目移除）。 */
    public void invalidate(Long definitionId) {
        cache.remove(definitionId);
        log.debug("编译缓存失效：definitionId={}", definitionId);
    }

    /** 库内定义 → 编译模型（XML 解析 + 校验 + 编译）。 */
    private CompiledProcessModel compile(WfProcessDefinition definition) {
        BpmnDocumentParser.ParseResult parseResult = BpmnDocumentParser.parse(definition.getBpmnXml());
        return ProcessModelCompiler.compile(definition.getId(), definition.getProcessKey(), parseResult);
    }
}
