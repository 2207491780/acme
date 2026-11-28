package com.acme.platform.workflow.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.Result;
import com.acme.platform.workflow.api.WorkflowDefinitionStatus;
import com.acme.platform.workflow.api.dto.ProcessDefinitionCreateRequest;
import com.acme.platform.workflow.api.dto.ProcessDefinitionView;
import com.acme.platform.workflow.api.dto.ProcessDefinitionXmlSaveRequest;
import com.acme.platform.workflow.application.DefinitionUseCase;
import com.acme.platform.workflow.compile.ProcessValidationException;
import com.acme.platform.workflow.compile.ValidationError;
import com.acme.framework.web.security.RequirePermission;
import com.acme.platform.system.api.SystemPermissions;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 流程定义管理 B 端接口（契约 API 定义域 6 端点；权限点对齐 platform-system V27 种子——确认⑦）：
 * <ul>
 *   <li>POST   /admin/v1/workflow/process-definitions            → workflow:definition:deploy（79，写面）</li>
 *   <li>PUT    /admin/v1/workflow/process-definitions/{id}/xml   → workflow:definition:deploy（79）</li>
 *   <li>POST   /admin/v1/workflow/process-definitions/{id}/deploy → workflow:definition:deploy（79）</li>
 *   <li>GET    /admin/v1/workflow/process-definitions/{id}       → workflow:definition:list（78）</li>
 *   <li>GET    /admin/v1/workflow/process-definitions            → workflow:definition:list（78）</li>
 *   <li>POST   /admin/v1/workflow/process-definitions/{id}/disable → workflow:definition:deploy（79）</li>
 * </ul>
 * 出参经 framework-web StandardResponseAdvice 统一 Result 信封（FR-003）；写端点 @AuditLog 语义
 * 面归 BPM-1 交付边界外（definition 域审计列随 wf_process_definition 行承载）。
 */
@RestController
@RequestMapping("/admin/v1/workflow/process-definitions")
public class AdminProcessDefinitionController {

    private final DefinitionUseCase definitionUseCase;

    public AdminProcessDefinitionController(DefinitionUseCase definitionUseCase) {
        this.definitionUseCase = definitionUseCase;
    }

    /** 创建草稿（撞键草稿 → platform-workflow-01-0001）。 */
    @PostMapping
    @RequirePermission(SystemPermissions.WORKFLOW_DEFINITION_DEPLOY)
    public ProcessDefinitionView create(@Valid @RequestBody ProcessDefinitionCreateRequest request) {
        Long id = definitionUseCase.create(request.key(), request.name(), request.bpmnXml());
        return definitionUseCase.get(id);
    }

    /** 保存 XML（草稿态；结构校验失败 → VALIDATION_FAILED 逐条错误信封 data）。 */
    @org.springframework.web.bind.annotation.PutMapping("/{id}/xml")
    @RequirePermission(SystemPermissions.WORKFLOW_DEFINITION_DEPLOY)
    public Result<Object> saveXml(@PathVariable Long id,
                                  @Valid @RequestBody ProcessDefinitionXmlSaveRequest request) {
        try {
            definitionUseCase.saveXml(id, request.bpmnXml(), request.layoutJson());
            return Result.ok(definitionUseCase.get(id));
        } catch (ProcessValidationException ex) {
            return validationFailure(ex);
        }
    }

    /** 部署（结构校验 → 编译缓存 → DEPLOYED；AC-2）。 */
    @PostMapping("/{id}/deploy")
    @RequirePermission(SystemPermissions.WORKFLOW_DEFINITION_DEPLOY)
    public Result<Object> deploy(@PathVariable Long id) {
        try {
            definitionUseCase.deploy(id);
            return Result.ok(definitionUseCase.get(id));
        } catch (ProcessValidationException ex) {
            return validationFailure(ex);
        }
    }

    /** 详情（含 bpmnXml）。 */
    @GetMapping("/{id}")
    @RequirePermission(SystemPermissions.WORKFLOW_DEFINITION_LIST)
    public ProcessDefinitionView get(@PathVariable Long id) {
        return definitionUseCase.get(id);
    }

    /** 分页（keyword 匹配 key/name、status 过滤、key+version 排序）。 */
    @GetMapping
    @RequirePermission(SystemPermissions.WORKFLOW_DEFINITION_LIST)
    public PageResult<ProcessDefinitionView> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) WorkflowDefinitionStatus status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return definitionUseCase.list(keyword, status, new PageQuery(page, size));
    }

    /** 禁用（新实例拒绝、运行实例不受影响；编译缓存失效——AC-13）。 */
    @PostMapping("/{id}/disable")
    @RequirePermission(SystemPermissions.WORKFLOW_DEFINITION_DEPLOY)
    public ProcessDefinitionView disable(@PathVariable Long id) {
        definitionUseCase.disable(id);
        return definitionUseCase.get(id);
    }

    /** 校验失败信封（逐条错误随 Result.data——Q-D05 结构化明细面）。 */
    private static Result<Object> validationFailure(ProcessValidationException ex) {
        List<ValidationError> errors = ex.getErrors();
        return Result.fail(com.acme.platform.workflow.WorkflowError.WORKFLOW_VALIDATION_FAILED,
                "流程结构校验未通过（" + errors.size() + " 条）", List.copyOf(errors));
    }
}
