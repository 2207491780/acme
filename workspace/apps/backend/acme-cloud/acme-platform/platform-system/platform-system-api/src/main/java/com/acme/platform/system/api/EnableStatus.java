package com.acme.platform.system.api;

/**
 * 启停状态（openapi EnableStatus：按枚举名序列化 / 反序列化，9.3.2 新增枚举值兼容）。
 * 独立于 common-core Enabled（后者按 code 序列化为 1/0，面向存储/内部编码），
 * 本枚举为对外契约 wire 形态，不得添加 code() 方法（EnumCodeModule 识别约定，Modules.md 2.5）。
 */
public enum EnableStatus {

    /** 启用。 */
    ENABLED,

    /** 停用。 */
    DISABLED
}
