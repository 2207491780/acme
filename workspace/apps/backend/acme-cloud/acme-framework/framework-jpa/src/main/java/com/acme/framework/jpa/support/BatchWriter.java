package com.acme.framework.jpa.support;

import com.acme.common.core.BizException;
import com.acme.framework.jpa.config.JpaProperties;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 批量写入（FR-012、6.6）：分块 persist + 周期 flush，flush 后仅脱管本批实体
 * （同事务其他托管实体不受影响，调用方后续更新本批实体须重新 merge）；
 * 批量失败统一转 {@code BizException(JpaError.BATCH_FAILED)} 并按事务语义回滚（10.2.1、10.5）。
 */
public class BatchWriter {

    private final EntityManager entityManager;
    private final int chunkSize;

    public BatchWriter(EntityManager entityManager, JpaProperties properties) {
        this(entityManager, properties.getBatch().getChunkSize());
    }

    public BatchWriter(EntityManager entityManager, int chunkSize) {
        if (entityManager == null) {
            throw new IllegalArgumentException("entityManager 不能为空");
        }
        if (chunkSize < 1) {
            throw new IllegalArgumentException("chunkSize 必须 ≥ 1");
        }
        this.entityManager = entityManager;
        this.chunkSize = chunkSize;
    }

    /** 批量持久化（FR-012）；返回写入条数。 */
    public <T> int persistAll(Collection<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        try {
            int count = 0;
            List<T> chunk = new ArrayList<>(chunkSize);
            for (T entity : entities) {
                if (entity == null) {
                    continue;
                }
                entityManager.persist(entity);
                chunk.add(entity);
                count++;
                if (chunk.size() >= chunkSize) {
                    flushAndDetach(chunk);
                    chunk.clear();
                }
            }
            if (!chunk.isEmpty()) {
                flushAndDetach(chunk);
            }
            return count;
        } catch (RuntimeException ex) {
            throw new BizException(JpaError.BATCH_FAILED, null, ex);
        }
    }

    /** 块大小（6.6）。 */
    public int chunkSize() {
        return chunkSize;
    }

    private <T> void flushAndDetach(List<T> chunk) {
        entityManager.flush();
        for (T entity : chunk) {
            entityManager.detach(entity);
        }
    }
}
