package com.acme.business.content.interfaces;

import com.acme.business.content.api.dto.AdminCategoryView;
import com.acme.business.content.api.dto.AdminContentRowView;
import com.acme.business.content.api.dto.AppCategoryView;
import com.acme.business.content.api.dto.AppContentDetailView;
import com.acme.business.content.api.dto.AppContentItemView;
import com.acme.business.content.api.dto.AppSearchSuggestionView;
import com.acme.business.content.domain.BizContent;
import com.acme.business.content.domain.BizContentCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 实体 → 视图对象转换（11.5.3 接口层完成；B/C 视图分离 11.5.1）。
 * Long 语义字段保持 Long（id 雪花 / views / version），wire 字符串化交 common-serialization
 * （G3 裁定：平台 Long→String 全局约定）；分类名经批量索引一次取（6.4 #3）。
 */
final class ContentViews {

    private ContentViews() {
    }

    static AppContentItemView toAppItem(BizContent content, Map<String, String> categoryNames) {
        return new AppContentItemView(
                content.getId(),
                content.getTitle(),
                content.getCategoryCode(),
                categoryNames.getOrDefault(content.getCategoryCode(), content.getCategoryCode()),
                content.getExcerpt());
    }

    static AppContentDetailView toAppDetail(BizContent content, List<BizContent> related,
                                            Map<String, String> categoryNames) {
        List<AppContentItemView> relatedViews = new ArrayList<>();
        for (BizContent item : related) {
            relatedViews.add(toAppItem(item, categoryNames));
        }
        return new AppContentDetailView(
                content.getId(),
                content.getTitle(),
                content.getCategoryCode(),
                categoryNames.getOrDefault(content.getCategoryCode(), content.getCategoryCode()),
                content.getExcerpt(),
                content.getContent(),
                content.getCover(),
                relatedViews);
    }

    static AppSearchSuggestionView toSuggestion(BizContent content) {
        return new AppSearchSuggestionView(content.getId(), content.getTitle());
    }

    static AdminContentRowView toAdminRow(BizContent content, Map<String, String> categoryNames) {
        return new AdminContentRowView(
                content.getId(),
                content.getTitle(),
                content.getCategoryCode(),
                categoryNames.getOrDefault(content.getCategoryCode(), content.getCategoryCode()),
                content.getAuthor(),
                content.getStatus(),
                content.getViews() == null ? 0L : content.getViews(),
                content.getUpdatedAt(),
                content.getVersion());
    }

    static AppCategoryView toAppCategory(BizContentCategory category) {
        return new AppCategoryView(category.getCode(), category.getName());
    }

    static AdminCategoryView toAdminCategory(BizContentCategory category) {
        return new AdminCategoryView(category.getCode(), category.getName());
    }
}
