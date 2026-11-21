package com.acme.platform.scheduler.api;

/**
 * misfire 策略（契约确认⑤：错过触发——启动恢复 / 停机期间跨整周期错过——的两种处置）。
 */
public enum MisfirePolicy {

    /** 补跑一次（默认）：错过窗口补发一次执行，随后跳到下一个未来触发点。 */
    FIRE_ONCE_NOW,

    /** 跳过：放弃错过窗口，直接跳到下一个未来触发点。 */
    IGNORE
}
