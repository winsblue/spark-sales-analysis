package com.sales.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置（OpenAPI 3，访问路径 /swagger-ui.html）。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI salesOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("电商商品销售分析平台 接口文档")
                .version("1.0.0")
                .description("基于 Spark 的电商商品销售离线分析与 Java 服务化应用 —— 后端接口说明。"
                        + "所有接口统一返回 { code, message, data } 结构，code=0 表示成功。")
                .contact(new Contact().name("生产实习项目组")));
    }
}
