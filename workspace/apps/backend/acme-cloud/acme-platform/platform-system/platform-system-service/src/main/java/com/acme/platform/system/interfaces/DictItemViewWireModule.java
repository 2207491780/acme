package com.acme.platform.system.interfaces;

import com.acme.framework.jackson.support.AcmeJacksonComponent;
import com.acme.platform.system.api.dto.AdminDictItemView;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.module.SimpleModule;

import java.util.List;

/**
 * 字典项视图 wire 塑形（REQ-20261125-230807 平铺零变化锚）：{@link AdminDictItemView#children()} 为
 * null 时整个键省略（{@code NON_NULL} mixin）——平铺读 / 创建 / 修改响应 wire 无 children 键
 * （V35 前现状）；kind=TREE 读子集恒非空，序列化为数组（叶子 []）。
 * -api 模块不引 Jackson 依赖，键省略在本服务出参侧承载（FR-008 组件注册面，随组件扫描入 JsonMapper）。
 */
@AcmeJacksonComponent
public class DictItemViewWireModule extends SimpleModule {

    public DictItemViewWireModule() {
        super("platform-system-dict-item-view-wire");
        setMixInAnnotation(AdminDictItemView.class, AdminDictItemViewMixin.class);
    }

    /** NON_NULL 声明在 children 访问器上（record 组件 mixin，匹配访问器 children()）。 */
    abstract static class AdminDictItemViewMixin {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public abstract List<AdminDictItemView> children();
    }
}
