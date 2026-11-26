package com.acme.platform.system.application;

import com.acme.common.core.BizException;
import com.acme.framework.web.support.WebError;
import com.acme.platform.system.api.DictKind;
import com.acme.platform.system.api.dto.AdminDictItemCreate;
import com.acme.platform.system.api.dto.AdminDictItemUpdate;
import com.acme.platform.system.domain.SysDictItem;
import com.acme.platform.system.domain.SysDictType;
import com.acme.platform.system.infrastructure.repository.SysDictItemRepository;
import com.acme.platform.system.infrastructure.repository.SysDictTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 字典树形校验测试（REQ-20261125-230807 确认④，AC-17）：FLAT 提交 parentId 拒绝、TREE 父条目
 * 存在/同类型/非自身或子孙（防环）校验；违规=VALIDATION_FAILED 400（PARAM_INVALID 口径既有码，零新增）。
 */
class DictUseCaseTreeValidationTest {

    private final SysDictTypeRepository typeRepository = mock(SysDictTypeRepository.class);
    private final SysDictItemRepository itemRepository = mock(SysDictItemRepository.class);

    private final DictUseCase useCase = new DictUseCase(typeRepository, itemRepository);

    private SysDictType treeType;
    private SysDictType flatType;

    @BeforeEach
    void setUp() {
        treeType = new SysDictType("region", "地区", null, DictKind.TREE);
        setId(treeType, 10L);
        flatType = new SysDictType("gender", "性别", null, DictKind.FLAT);
        setId(flatType, 20L);
        when(typeRepository.findById(10L)).thenReturn(Optional.of(treeType));
        when(typeRepository.findById(20L)).thenReturn(Optional.of(flatType));
    }

    @Test
    void flatTypeRejectsParentId() {
        assertThatThrownBy(() -> useCase.createItem(20L,
                new AdminDictItemCreate("男", "M", 0, 1L)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode().code())
                .isEqualTo(WebError.VALIDATION_FAILED.code());
    }

    @Test
    void flatTypeNullParentIdKeepsLegacyBehavior() {
        // FLAT 提交 parentId=null：既有平铺行为零变化
        when(itemRepository.existsByDictType_IdAndItemValueAndIdNot(20L, "M", -1L)).thenReturn(false);
        when(itemRepository.save(any(SysDictItem.class))).thenAnswer(inv -> inv.getArgument(0));
        SysDictItem created = useCase.createItem(20L, new AdminDictItemCreate("男", "M", 0));
        assertThat(created.getParentId()).isNull();
        assertThat(created.getDictTypeId()).isEqualTo(20L);
    }

    @Test
    void treeTypeMissingParentRejected() {
        when(itemRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.createItem(10L,
                new AdminDictItemCreate("上海", "SH", 0, 99L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("父条目不存在");
    }

    @Test
    void treeTypeCrossTypeParentRejected() {
        SysDictItem foreign = new SysDictItem(flatType, "男", "M", 0);
        setId(foreign, 30L);
        when(itemRepository.findById(30L)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> useCase.createItem(10L,
                new AdminDictItemCreate("上海", "SH", 0, 30L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("同一字典类型");
    }

    @Test
    void treeTypeSelfParentRejectedOnUpdate() {
        SysDictItem self = new SysDictItem(treeType, "上海", "SH", 0);
        setId(self, 2L);
        self.setVersion(0L);
        when(itemRepository.findById(2L)).thenReturn(Optional.of(self));
        assertThatThrownBy(() -> useCase.updateItem(2L,
                new AdminDictItemUpdate("上海", "SH", 0, 0L, 2L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("父条目不能为自身");
    }

    @Test
    void treeTypeDescendantParentRejectedOnUpdate() {
        // 链：1(华东) → 2(上海) → 3(浦东)；把 1 的父改为 3（1 的孙）→ 防环拒绝
        SysDictItem east = itemOf(1L, 10L, null);
        SysDictItem shanghai = itemOf(2L, 10L, 1L);
        SysDictItem pudong = itemOf(3L, 10L, 2L);
        when(itemRepository.findById(1L)).thenReturn(Optional.of(east));
        when(itemRepository.findById(3L)).thenReturn(Optional.of(pudong));
        // 防环上行链路：3(浦东) → 2(上海) → 命中自身 1
        when(itemRepository.findById(2L)).thenReturn(Optional.of(shanghai));
        assertThatThrownBy(() -> useCase.updateItem(1L,
                new AdminDictItemUpdate("华东", "EAST", 0, 0L, 3L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("父条目不能为自身子孙");
    }

    @Test
    void treeTypeValidParentAcceptedAndApplied() {
        SysDictItem east = itemOf(1L, 10L, null);
        when(itemRepository.findById(1L)).thenReturn(Optional.of(east));
        when(itemRepository.existsByDictType_IdAndItemValueAndIdNot(10L, "SH", -1L)).thenReturn(false);
        when(itemRepository.save(any(SysDictItem.class))).thenAnswer(inv -> inv.getArgument(0));
        SysDictItem created = useCase.createItem(10L, new AdminDictItemCreate("上海", "SH", 0, 1L));
        assertThat(created.getParentId()).isEqualTo(1L);
        assertThat(created.getDictTypeId()).isEqualTo(10L);
    }

    @Test
    void updateClearParentAppliesNullForTree() {
        // 全值语义：TREE 更新 parentId=null → 清父（置顶）
        SysDictItem shanghai = itemOf(2L, 10L, 1L);
        when(itemRepository.findById(2L)).thenReturn(Optional.of(shanghai));
        when(itemRepository.existsByDictType_IdAndItemValueAndIdNot(10L, "SH", 2L)).thenReturn(false);
        when(itemRepository.save(any(SysDictItem.class))).thenAnswer(inv -> inv.getArgument(0));
        SysDictItem updated = useCase.updateItem(2L, new AdminDictItemUpdate("上海", "SH", 0, 0L, null));
        assertThat(updated.getParentId()).isNull();
    }

    @Test
    void flatTypeUpdateRejectsParentId() {
        SysDictItem m = itemOf(1L, 20L, null);
        when(itemRepository.findById(1L)).thenReturn(Optional.of(m));
        assertThatThrownBy(() -> useCase.updateItem(1L,
                new AdminDictItemUpdate("男", "M", 0, 0L, 1L)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode().code())
                .isEqualTo(WebError.VALIDATION_FAILED.code());
    }

    private static SysDictItem itemOf(long id, long typeId, Long parentId) {
        SysDictItem item = new SysDictItem(null, "label", "value", 0);
        try {
            var idField = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(item, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        item.setDictTypeId(typeId);
        item.setParentId(parentId);
        // 乐观锁版本（用例层 request.version 等值比对锚点）
        item.setVersion(0L);
        return item;
    }

    private static void setId(SysDictType type, long id) {
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(type, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 条目夹具 id 回填（AuditBase 同基类反射，与类型版同口径）。 */
    private static void setId(SysDictItem item, long id) {
        try {
            var field = com.acme.framework.flex.support.AuditBase.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(item, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
