package com.acme.business.leave;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * business-leave 聚合配置（REQ-20261122-233425 确认⑤⑥交付形态：bootstrap 单体聚合——
 * MonolithApplication @Import 显式导入本类；scanBasePackages 不动（business 包不在主应用
 * 扫描面，各服务组件面扫描口径不变））。
 *
 * <p>@MapperScan：bootstrap 主应用 MapperScan 面为 com.acme.platform——business mapper 显式
 * 收编（annotationClass=Mapper 同口径）；standalone 服务化（不在本期）由自身 Application 主包
 * 自动注册语义承接。</p>
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "com.acme.business.leave")
@MapperScan(basePackages = "com.acme.business.leave", annotationClass = Mapper.class)
public class BusinessLeaveConfiguration {
}
