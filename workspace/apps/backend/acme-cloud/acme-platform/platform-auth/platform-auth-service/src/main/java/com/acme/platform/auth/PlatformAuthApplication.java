package com.acme.platform.auth;

import com.acme.platform.auth.internal.token.UserTokenIssuer;
import com.acme.platform.auth.config.AuthTokenProperties;
import com.acme.platform.auth.config.LoginGuardProperties;
import com.acme.platform.system.api.AuthProfileClient;
import com.acme.platform.system.api.AuthorizationClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 认证域平台服务启动入口（G2.5 拆分评审结论；18.1.1 第 5 步）。
 * 模块名 = 应用名 = 配置中心标识（5.4.3）：platform-auth；
 * 服务间依赖仅 platform-system-api（auth → system 单向，4.4.2）。
 */
@SpringBootApplication
@EnableFeignClients(clients = {AuthProfileClient.class, AuthorizationClient.class})
@EnableConfigurationProperties({AuthTokenProperties.class, LoginGuardProperties.class})
public class PlatformAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformAuthApplication.class, args);
    }

    /** 用户令牌签发器（构造期派生密钥：空密钥启动即失败，14.3.3 / TokenKeys 单一失败点）。 */
    @Bean
    public UserTokenIssuer userTokenIssuer(AuthTokenProperties properties) {
        return new UserTokenIssuer(properties);
    }

    /** BCrypt 散列器（FR-005 不可逆存储；cost 10 与 pgcrypto 种子 gen_salt('bf', 10) 对齐，Q-D05）。 */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
