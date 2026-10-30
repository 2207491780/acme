package com.acme.business.content.interfaces;

import com.acme.business.content.ContentError;
import com.acme.common.core.BizException;

/**
 * 路径 id 解析（雪花字符串化对外，12.3.1）：
 * 管理侧解析失败按内容不存在（404 business-content-01-0001）；C 端详情宽容解析（空态口径）。
 */
final class PathIds {

    private PathIds() {
    }

    /** 管理侧：解析失败 = 内容不存在（404）。 */
    static Long parseRequired(String raw) {
        Long id = parseLenient(raw);
        if (id == null) {
            throw new BizException(ContentError.CONTENT_NOT_FOUND);
        }
        return id;
    }

    /** C 端详情：解析失败返回 null（调用方按空态处理，openapi 空 data 口径）。 */
    static Long parseLenient(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
