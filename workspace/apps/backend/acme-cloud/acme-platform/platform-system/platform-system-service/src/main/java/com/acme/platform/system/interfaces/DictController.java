package com.acme.platform.system.interfaces;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
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
import com.acme.platform.system.security.RequirePermission;
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
    public AdminDictTypeView createType(@Valid @RequestBody AdminDictTypeCreate request) {
        return EntityViews.dictTypeView(dictUseCase.createType(request));
    }

    @PutMapping("/dict-types/{typeId}")
    @RequirePermission(SystemPermissions.DICT_UPDATE)
    public AdminDictTypeView updateType(@PathVariable Long typeId, @Valid @RequestBody AdminDictTypeUpdate request) {
        return EntityViews.dictTypeView(dictUseCase.updateType(typeId, request));
    }

    @DeleteMapping("/dict-types/{typeId}")
    @RequirePermission(SystemPermissions.DICT_DELETE)
    public void deleteType(@PathVariable Long typeId) {
        dictUseCase.deleteType(typeId);
    }

    @GetMapping("/dict-types/{typeId}/items")
    @RequirePermission(SystemPermissions.DICT_LIST)
    public List<AdminDictItemView> listItems(@PathVariable Long typeId) {
        return dictUseCase.listItems(typeId).stream().map(EntityViews::dictItemView).toList();
    }

    @PostMapping("/dict-types/{typeId}/items")
    @RequirePermission(SystemPermissions.DICT_CREATE)
    public AdminDictItemView createItem(@PathVariable Long typeId, @Valid @RequestBody AdminDictItemCreate request) {
        return EntityViews.dictItemView(dictUseCase.createItem(typeId, request));
    }

    @PutMapping("/dict-items/{itemId}")
    @RequirePermission(SystemPermissions.DICT_UPDATE)
    public AdminDictItemView updateItem(@PathVariable Long itemId, @Valid @RequestBody AdminDictItemUpdate request) {
        return EntityViews.dictItemView(dictUseCase.updateItem(itemId, request));
    }

    @DeleteMapping("/dict-items/{itemId}")
    @RequirePermission(SystemPermissions.DICT_DELETE)
    public void deleteItem(@PathVariable Long itemId) {
        dictUseCase.deleteItem(itemId);
    }
}
