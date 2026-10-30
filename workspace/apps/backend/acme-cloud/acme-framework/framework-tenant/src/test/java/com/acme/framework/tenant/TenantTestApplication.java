package com.acme.framework.tenant;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 切片测试引导配置（@DataJpaTest 向上查找 @SpringBootConfiguration，仅测试域）。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class TenantTestApplication {
}
