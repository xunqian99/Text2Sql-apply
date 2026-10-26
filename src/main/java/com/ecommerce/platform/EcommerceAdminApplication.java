package com.ecommerce.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 电商运营与智能问数中台系统 (E-Commerce Ops & AI Copilot Platform) 主启动类。
 *
 * <p>同时扫描业务中台包 (com.ecommerce.platform) 与 Text2SQL 智能体包 (com.text2sql.agent)。
 */
@SpringBootApplication(scanBasePackages = {
        "com.ecommerce.platform",
        "com.text2sql.agent"
})
public class EcommerceAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcommerceAdminApplication.class, args);
    }
}
