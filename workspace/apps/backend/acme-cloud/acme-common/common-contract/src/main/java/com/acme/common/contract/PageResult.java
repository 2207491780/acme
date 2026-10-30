package com.acme.common.contract;

import java.util.List;

/**
 * 分页响应协议基元（FR-023；Modules.md 2.1 推荐 record）：服务内与跨服务统一分页响应类型。
 * records 做防御性拷贝（不可变），空值归一为空集与零总数，容忍薄 JSON 输入。
 *
 * @implNote 暂缓 DP-008：与业务查询返回模型的组合方式随首个使用方补全。
 */
public record PageResult<T>(List<T> records, Long total) {

    public PageResult {
        records = records == null ? List.of() : List.copyOf(records);
        total = total == null ? 0L : total;
    }

    /** 便捷工厂：空结果页。 */
    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), 0L);
    }
}
