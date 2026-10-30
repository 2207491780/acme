package com.acme.common.contract;

/**
 * 错误码分段约定（FR-022；Q-004 结论：结构 + 分配规则；Q-010 结论：文本三段式）。
 *
 * <p>结构：{@code {serviceId}-{模块段}-{序号}}（如 {@code order-10-0001}）；编码宽度按 Q-010：
 * 模块段 2 位、序号 4 位、不足左侧补零、上界随宽度（模块段 ≤ 99、序号 ≤ 9999，本次定稿）。</p>
 *
 * <p>分配规则（契约级语义）：</p>
 * <ol>
 *   <li>全局唯一，按「服务标识 + 模块段 + 序号」分配（Backend.md 10.5.2、BR-017）；</li>
 *   <li>越段（moduleSegment 不在授权区间）判定为不合法；</li>
 *   <li>已废弃错误码不得复用（BR-017）。</li>
 * </ol>
 *
 * <p>段位表（DP-008 本次定稿，Q-004 口径登记于此，不另建类；P5 按需模块续编 SYS-11~19，p5-optional-modules D-004）：</p>
 * <ul>
 *   <li>保留 serviceId {@code SYS}：common / framework 共享错误码。模块段：00=common-core，
 *       01=framework-web，02=framework-security，03=framework-gateway，04=framework-jpa，
 *       05=framework-tenant，06=framework-cache，07=framework-lock，08=framework-stream，
 *       09=framework-feign，10=framework-job，11=framework-idempotent，12=framework-excel，
 *       13=framework-i18n，14=framework-audit，15=framework-storage，16=framework-doc，
 *       17=framework-ratelimit，18=framework-observability，19=framework-tx；
 *       段内序号自 0001 起递增，废弃不复用（BR-017）。</li>
 *   <li>服务自有错误码 serviceId 用服务名（小写短横线，Backend.md 5.4.3），模块段由服务自管。</li>
 * </ul>
 *
 * @implNote 暂缓 DP-015：使用样例与更细方法级契约随实现补全。
 */
public record ErrorCodeLayout(String serviceId, Integer moduleSegment, Integer serial) {

    public ErrorCodeLayout {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId 不能为空（DP-008）");
        }
        if (moduleSegment == null || moduleSegment < 0 || moduleSegment > 99) {
            throw new IllegalArgumentException("moduleSegment 非法（DP-008）：" + moduleSegment);
        }
        if (serial == null || serial < 0 || serial > 9999) {
            throw new IllegalArgumentException("serial 非法（DP-008）：" + serial);
        }
    }

    /**
     * 逆解析（FR-022）；结构非法显式失败（IllegalArgumentException）。
     * 越段治理按 javadoc 段位表由分配方自律，配各模块错误码段位断言测试锁定（DP-008 已定稿）。
     */
    public static ErrorCodeLayout parse(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("错误码不能为空（DP-008）");
        }
        int lastHyphen = code.lastIndexOf('-');
        int secondLastHyphen = lastHyphen < 0 ? -1 : code.lastIndexOf('-', lastHyphen - 1);
        if (secondLastHyphen <= 0 || lastHyphen <= secondLastHyphen + 1 || lastHyphen >= code.length() - 1) {
            throw new IllegalArgumentException("错误码结构非法（DP-008）：" + code);
        }
        String serviceId = code.substring(0, secondLastHyphen);
        String segmentText = code.substring(secondLastHyphen + 1, lastHyphen);
        String serialText = code.substring(lastHyphen + 1);
        try {
            return new ErrorCodeLayout(serviceId, Integer.valueOf(segmentText), Integer.valueOf(serialText));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("错误码结构非法（DP-008）：" + code, e);
        }
    }

    /**
     * 按文本三段式拼接（FR-022；Q-010）：模块段 2 位、序号 4 位、不足左侧补零。
     */
    public String code() {
        return serviceId + "-" + String.format("%02d-%04d", moduleSegment, serial);
    }
}
