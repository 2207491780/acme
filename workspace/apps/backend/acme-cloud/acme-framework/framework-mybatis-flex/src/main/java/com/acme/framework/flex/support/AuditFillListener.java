package com.acme.framework.flex.support;

import com.acme.framework.flex.internal.AuditSupport;
import com.mybatisflex.annotation.InsertListener;
import com.mybatisflex.annotation.UpdateListener;

import java.time.Instant;

/**
 * 审计填充监听器（FR-008/009/010，12.2）：雪花主键赋值 + 审计缺省填充 + 乐观锁版本初值。
 * 由 FlexAutoConfiguration 经 MyBatisFlexCustomizer 注册在基类 {@link AuditBase} 上
 * （FlexGlobalConfig 按 isAssignableFrom 匹配，覆盖所有子类实体，12.3.1、6.2）。
 */
public class AuditFillListener implements InsertListener, UpdateListener {

    @Override
    public void onInsert(Object entity) {
        if (entity instanceof AuditBase audit) {
            audit.applyInsertDefaults(AuditSupport.nextId(), AuditSupport.operator(), Instant.now());
        }
    }

    @Override
    public void onUpdate(Object entity) {
        if (entity instanceof AuditBase audit) {
            audit.applyUpdateDefaults(AuditSupport.operator(), Instant.now());
        }
    }
}
