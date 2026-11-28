package com.acme.platform.system.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.Result;
import com.acme.framework.audit.support.AuditLog;
import com.acme.platform.system.api.SystemPermissions;
import com.acme.platform.system.api.dto.AdminDictItemCreate;
import com.acme.platform.system.api.dto.AdminDictItemUpdate;
import com.acme.platform.system.api.dto.AdminDictItemView;
import com.acme.platform.system.api.dto.AdminDictTypeCreate;
import com.acme.platform.system.api.dto.AdminDictTypeUpdate;
import com.acme.platform.system.api.dto.AdminDictTypeView;
import com.acme.platform.system.application.DictUseCase;
import com.acme.platform.system.domain.SysDictItem;
import com.acme.platform.system.domain.SysDictType;
import com.acme.framework.web.security.RequirePermission;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 字典管理接口（FR-006：字典类型 + 字典项）。
 */
@RestController
@RequestMapping("/admin/v1/system")
public class DictController {

    private final DictUseCase dictUseCase;

    public DictController(DictUseCase dictUseCase) {
        this.dictUseCase = dictUseCase;
    }

    @GetMapping("/dict-types")
    @RequirePermission(SystemPermissions.DICT_LIST)
    public PageResult<AdminDictTypeView> listTypes(@RequestParam(defaultValue = "1") Integer page,
                                                   @RequestParam(defaultValue = "20") Integer size,
                                                   @RequestParam(required = false) String keyword) {
        PageResult<SysDictType> result = dictUseCase.pageTypes(new PageQuery(page, size), keyword);
        return new PageResult<>(
                result.records().stream().map(EntityViews::dictTypeView).toList(), result.total());
    }

    @PostMapping("/dict-types")
    @RequirePermission(SystemPermissions.DICT_CREATE)
    @AuditLog(action = "system.dict.createType", targetType = "dictType",
            contentParams = {"request.dictCode", "request.dictName"})
    public AdminDictTypeView createType(@Valid @RequestBody AdminDictTypeCreate request) {
        return EntityViews.dictTypeView(dictUseCase.createType(request));
    }

    @PutMapping("/dict-types/{typeId}")
    @RequirePermission(SystemPermissions.DICT_UPDATE)
    @AuditLog(action = "system.dict.updateType", targetType = "dictType", targetId = "#typeId",
            beforeQuery = "@dictUseCase.getType(#typeId)",
            contentParams = {"typeId", "request.dictName"})
    public AdminDictTypeView updateType(@PathVariable Long typeId, @Valid @RequestBody AdminDictTypeUpdate request) {
        return EntityViews.dictTypeView(dictUseCase.updateType(typeId, request));
    }

    /** 删除字典类型（统一响应体 Result 契约口径：void 语义显式出信封，code="0"；REQ-20261125-230807）。 */
    @DeleteMapping("/dict-types/{typeId}")
    @RequirePermission(SystemPermissions.DICT_DELETE)
    @AuditLog(action = "system.dict.deleteType", targetType = "dictType", targetId = "#typeId",
            contentParams = {"typeId"})
    public Result<Void> deleteType(@PathVariable Long typeId) {
        dictUseCase.deleteType(typeId);
        return Result.ok(null);
    }

    /** 字典项列表（REQ-20261125-230807：kind=TREE 嵌套树返回，kind=FLAT 平铺现状不变；children 恒数组）。 */
    @GetMapping("/dict-types/{typeId}/items")
    @RequirePermission(SystemPermissions.DICT_LIST)
    public List<AdminDictItemView> listItems(@PathVariable Long typeId) {
        SysDictType type = dictUseCase.getType(typeId);
        return EntityViews.dictItemViews(type.getDictKind(), dictUseCase.listItems(typeId));
    }

    @PostMapping("/dict-types/{typeId}/items")
    @RequirePermission(SystemPermissions.DICT_CREATE)
    @AuditLog(action = "system.dict.createItem", targetType = "dictItem",
            contentParams = {"typeId", "request.itemLabel", "request.itemValue"})
    public AdminDictItemView createItem(@PathVariable Long typeId, @Valid @RequestBody AdminDictItemCreate request) {
        return EntityViews.dictItemView(dictUseCase.createItem(typeId, request));
    }

    @PutMapping("/dict-items/{itemId}")
    @RequirePermission(SystemPermissions.DICT_UPDATE)
    @AuditLog(action = "system.dict.updateItem", targetType = "dictItem", targetId = "#itemId",
            contentParams = {"itemId", "request.itemLabel", "request.itemValue"})
    public AdminDictItemView updateItem(@PathVariable Long itemId, @Valid @RequestBody AdminDictItemUpdate request) {
        return EntityViews.dictItemView(dictUseCase.updateItem(itemId, request));
    }

    /** 删除字典项（统一响应体 Result 契约口径：void 语义显式出信封，code="0"；REQ-20261125-230807）。 */
    @DeleteMapping("/dict-items/{itemId}")
    @RequirePermission(SystemPermissions.DICT_DELETE)
    @AuditLog(action = "system.dict.deleteItem", targetType = "dictItem", targetId = "#itemId",
            contentParams = {"itemId"})
    public Result<Void> deleteItem(@PathVariable Long itemId) {
        dictUseCase.deleteItem(itemId);
        return Result.ok(null);
    }
}
