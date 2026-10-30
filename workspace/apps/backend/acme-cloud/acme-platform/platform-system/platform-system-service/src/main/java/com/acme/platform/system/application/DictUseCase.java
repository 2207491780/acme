package com.acme.platform.system.application;

import com.acme.common.contract.PageQuery;
import com.acme.common.contract.PageResult;
import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.SystemError;
import com.acme.platform.system.api.dto.AdminDictItemCreate;
import com.acme.platform.system.api.dto.AdminDictItemUpdate;
import com.acme.platform.system.api.dto.AdminDictTypeCreate;
import com.acme.platform.system.api.dto.AdminDictTypeUpdate;
import com.acme.platform.system.domain.SysDictItem;
import com.acme.platform.system.domain.SysDictType;
import com.acme.platform.system.infrastructure.repository.SysDictItemRepository;
import com.acme.platform.system.infrastructure.repository.SysDictTypeRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 字典管理用例（FR-006，架构设计 5.1 字典两行；租户归属，dictCode / itemValue 唯一约束在租户内）。
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
        return dictTypeRepository.save(new SysDictType(request.dictCode(), request.dictName(), request.remark()));
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
        Specification<SysDictType> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(cb.like(root.get("dictCode"), like), cb.like(root.get("dictName"), like)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<SysDictType> page = dictTypeRepository.findAll(spec,
                PageRequest.of(query.page() - 1, query.size(), Sort.by(Sort.Direction.DESC, "id")));
        return new PageResult<>(page.getContent(), page.getTotalElements());
    }

    @Transactional
    public SysDictItem createItem(Long typeId, AdminDictItemCreate request) {
        SysDictType type = getType(typeId);
        if (dictItemRepository.existsByDictType_IdAndItemValueAndIdNot(typeId, request.itemValue(), -1L)) {
            throw new BizException(SystemError.DICT_ITEM_DUPLICATED);
        }
        return dictItemRepository.save(new SysDictItem(type, request.itemLabel(), request.itemValue(),
                request.sortOrder()));
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
        item.setItemLabel(request.itemLabel());
        item.setItemValue(request.itemValue());
        item.setSortOrder(request.sortOrder());
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
}
