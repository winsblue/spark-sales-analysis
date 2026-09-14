package com.sales.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 后端服务启动类。
 *
 * <p>职责：读取离线分析作业产出的指标结果表（ADS 层），
 * 通过 RESTful 接口对外提供指标查询、多维筛选、明细下钻与数据导出能力。</p>
 *
 * <p>启动后访问：</p>
 * <ul>
 *   <li>可视化看板：http://localhost:8080/</li>
 *   <li>接口文档：http://localhost:8080/swagger-ui.html</li>
 * </ul>
 */
@SpringBootApplication
@MapperScan("com.sales.server.mapper")
public class SalesServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalesServerApplication.class, args);
        System.out.println("\n======================================================");
        System.out.println("  电商销售分析平台后端服务启动成功");
        System.out.println("  可视化看板 : http://localhost:8080/");
        System.out.println("  接口文档   : http://localhost:8080/swagger-ui.html");
        System.out.println("======================================================\n");
    }
}
