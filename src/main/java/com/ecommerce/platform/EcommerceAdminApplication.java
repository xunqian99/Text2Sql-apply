package com.ecommerce.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 电商运营与智能问数中台系统 (E-Commerce Ops & AI Copilot Platform) 主启动类。
 *
 * <p>本系统融合传统电商业务中台（商品、订单与指标字典 CRUD）与
 * 前沿 Text2SQL 智能体（自然语言问数、AST 安全沙箱防护、ECharts 图表自动渲染）。
 */
@SpringBootApplication
public class EcommerceAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcommerceAdminApplication.class, args);
    }
}
