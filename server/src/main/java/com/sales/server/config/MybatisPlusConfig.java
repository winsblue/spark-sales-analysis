package com.sales.server.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：注册分页插件。
 *
 * <p>分页插件用于明细下钻页面的物理分页（LIMIT ?,?），
 * 避免一次查出全部明细造成内存压力。</p>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大 1000 条，防止前端传入过大分页参数
        pagination.setMaxLimit(1000L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
