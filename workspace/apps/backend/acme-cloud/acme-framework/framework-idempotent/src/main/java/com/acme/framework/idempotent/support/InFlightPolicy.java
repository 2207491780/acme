package com.acme.framework.idempotent.support;

/**
 * 在途重复请求处置（Q-D002 已确认：默认 WAIT 有界等待取结果，可配 REJECT）。
 */
public enum InFlightPolicy {

    /** 有界等待取缓存结果，超时转在途错误（默认 wait 3s）。 */
    WAIT,

    /** 快速失败（409）。 */
    REJECT
}
