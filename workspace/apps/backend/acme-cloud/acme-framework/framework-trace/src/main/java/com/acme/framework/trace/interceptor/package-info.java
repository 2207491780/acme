/**
 * framework-trace 三通道拦截组件（架构设计 3.1）：HTTP 入口、Feign 发送侧、Stream 发送侧。
 * 通道三方接口为 optional 依赖，类加载由装配条件隔离（架构设计 3.8）。
 */
package com.acme.framework.trace.interceptor;
