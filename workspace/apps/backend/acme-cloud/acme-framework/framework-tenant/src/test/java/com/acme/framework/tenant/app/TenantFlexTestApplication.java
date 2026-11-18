package com.acme.framework.tenant.app;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 测试装配入口（15 章测试设计 6：等效隔离环境）：独立包避免组件扫描触碰框架自动装配类；
 * mapper 显式扫描本包（@MapperScan）。
 */
@SpringBootApplication
@MapperScan(value = "com.acme.framework.tenant.app", annotationClass = Mapper.class)
public class TenantFlexTestApplication {
}
