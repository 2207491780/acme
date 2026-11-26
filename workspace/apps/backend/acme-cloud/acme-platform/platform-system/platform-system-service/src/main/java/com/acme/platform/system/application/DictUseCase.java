package com.acme.platform.system.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.DictKind;
import com.acme.platform.system.api.dto.AdminDictItemCreate;
import com.acme.platform.system.api.dto.AdminDictItemUpdate;
import com.acme.platform.system.api.dto.AdminDictTypeCreate;
import com.acme.platform.system.api.dto.AdminDictTypeUpdate;
import com.acme.platform.system.domain.SysDictItem;
import com.acme.platform.system.domain.SysDictType;
import com.acme.platform.system.infrastructure.repository.SysDictItemRepository;
import com.acme.platform.system.infrastructure.repository.SysDictTypeRepository;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 字典管理用例（FR-006，架构设计 5.1 字典两行；租户归属，dictCode / itemValue 唯一约束在租户内）。
 * REQ-20261125-230807 树形支持：kind=TREE 条目可挂父（校验父存在/同类型/非自身或子孙——防环），
 * FLAT 类型提交 parentId 拒绝（VALIDATION_FAILED 400=PARAM_INVALID 口径既有码，零新增错误码）；
 * 平铺既有行为零变化（存量行迁移后全为 FLAT 默认值）。
 */
@Service
public class DictUseCase {

    private final SysDictTypeRepository dictTypeRepository;
    private final SysDictItemRepository dictItemRepository;

    public DictUseCase(SysDictTypeRepository dictTypeRepository, SysDictItemRepository dictItemRepository) {
        this.dictTypeRepository = dictTypeRepository;
        this.dictItemRepository = dictItemRepository;
    }

    @Transactional
    public SysDictType createType(AdminDictTypeCreate request) {
        if (dictTypeRepository.existsByDictCodeAndIdNot(request.dictCode(), -1L)) {
            throw new BizException(SystemError.DICT_CODE_DUPLICATED);
        }
        return dictTypeRepository.save(new SysDictType(request.dictCode(), request.dictName(),
                request.remark(), request.dictKind()));
    }

    public SysDictType getType(Long typeId) {
        return dictTypeRepository.findById(typeId)
                .orElseThrow(() -> new BizException(SystemError.DICT_TYPE_NOT_FOUND));
    }

    @Transactional
    public SysDictType updateType(Long typeId, AdminDictTypeUpdate request) {
        SysDictType type = getType(typeId);
        if (request.version() == null || !request.version().equals(type.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        type.setDictName(request.dictName());
        type.setRemark(request.remark());
        return dictTypeRepository.save(type);
    }

    /** 删除字典类型（存在字典项拒绝；幂等）。 */
    @Transactional
    public void deleteType(Long typeId) {
        dictTypeRepository.findById(typeId).ifPresent(type -> {
            if (dictItemRepository.existsByDictType_Id(typeId)) {
                throw new BizException(SystemError.DICT_TYPE_DELETE_DENIED);
            }
            dictTypeRepository.delete(type);
        });
    }

    public PageResult<SysDictType> pageTypes(PageQuery query, String keyword) {
        QueryWrapper wrapper = QueryWrapper.create();
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.where(SysDictType::getDictCode).like(like).or(SysDictType::getDictName).like(like);
        }
        wrapper.orderBy(SysDictType::getId, false);
        return dictTypeRepository.findAll(wrapper, query.page(), query.size());
    }

    @Transactional
    public SysDictItem createItem(Long typeId, AdminDictItemCreate request) {
        SysDictType type = getType(typeId);
        validateItemParent(type, request.parentId(), null);
        if (dictItemRepository.existsByDictType_IdAndItemValueAndIdNot(typeId, request.itemValue(), -1L)) {
            throw new BizException(SystemError.DICT_ITEM_DUPLICATED);
        }
        SysDictItem item = new SysDictItem(type, request.itemLabel(), request.itemValue(), request.sortOrder());
        item.setParentId(request.parentId());
        return dictItemRepository.save(item);
    }

    public SysDictItem getItem(Long itemId) {
        return dictItemRepository.findById(itemId)
                .orElseThrow(() -> new BizException(SystemError.DICT_TYPE_NOT_FOUND));
    }

    @Transactional
    public SysDictItem updateItem(Long itemId, AdminDictItemUpdate request) {
        SysDictItem item = getItem(itemId);
        if (request.version() == null || !request.version().equals(item.getVersion())) {
            throw new BizException(WebError.CONCURRENCY_CONFLICT);
        }
        if (dictItemRepository.existsByDictType_IdAndItemValueAndIdNot(item.getDictTypeId(),
                request.itemValue(), itemId)) {
            throw new BizException(SystemError.DICT_ITEM_DUPLICATED);
        }
        SysDictType type = getType(item.getDictTypeId());
        validateItemParent(type, request.parentId(), itemId);
        item.setItemLabel(request.itemLabel());
        item.setItemValue(request.itemValue());
        item.setSortOrder(request.sortOrder());
        item.setParentId(request.parentId());
        return dictItemRepository.save(item);
    }

    /** 删除字典项（幂等软删）。 */
    @Transactional
    public void deleteItem(Long itemId) {
        dictItemRepository.findById(itemId).ifPresent(dictItemRepository::delete);
    }

    public List<SysDictItem> listItems(Long typeId) {
        getType(typeId);
        return dictItemRepository.findByDictType_IdOrderBySortOrderAscIdAsc(typeId);
    }

    /**
     * 条目父级写入校验（REQ-20261125-230807，违规=VALIDATION_FAILED 400，PARAM_INVALID 口径既有码）：
     * FLAT 类型提交 parentId 拒绝；TREE 类型父条目须存在、同字典类型、非自身/子孙（防环）。
     * {@code selfItemId} 传 null=新建语义（新条目不可能是他人祖先，仅存在性 / 归属校验）。
     */
    private void validateItemParent(SysDictType type, Long parentId, Long selfItemId) {
        if (type.getDictKind() != DictKind.TREE) {
            if (parentId != null) {
                throw new BizException(WebError.VALIDATION_FAILED,
                        "平铺字典不支持父条目（dictKind=FLAT）");
            }
            return;
        }
        if (parentId == null) {
            return;
        }
        if (parentId.equals(selfItemId)) {
            throw new BizException(WebError.VALIDATION_FAILED, "父条目不能为自身");
        }
        SysDictItem parent = dictItemRepository.findById(parentId)
                .orElseThrow(() -> new BizException(WebError.VALIDATION_FAILED,
                        "父条目不存在: " + parentId));
        if (!parent.getDictTypeId().equals(type.getId())) {
            throw new BizException(WebError.VALIDATION_FAILED, "父条目须属同一字典类型");
        }
        if (selfItemId != null) {
            // 防环：候选父沿 parent 链上行，命中自身即父在自身子树内（候选父为自身子孙）
            Long cursor = parent.getParentId();
            while (cursor != null) {
                if (cursor.equals(selfItemId)) {
                    throw new BizException(WebError.VALIDATION_FAILED, "父条目不能为自身子孙");
                }
                cursor = dictItemRepository.findById(cursor).map(SysDictItem::getParentId).orElse(null);
            }
        }
    }
}
