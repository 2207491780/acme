package com.acme.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统一接入网关启动类（Backend.md 5.3 网关部署单元；既有单元补全，FR-027）。
 */
@SpringBootApplication
public class AcmeGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AcmeGatewayApplication.class, args);
    }
}
