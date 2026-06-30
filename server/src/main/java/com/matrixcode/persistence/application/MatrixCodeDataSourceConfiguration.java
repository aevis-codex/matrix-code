package com.matrixcode.persistence.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(prefix = "matrixcode.persistence", name = "mode", havingValue = "jdbc")
public class MatrixCodeDataSourceConfiguration {

    /**
     * 创建正式持久化使用的数据源。
     *
     * <p>该 Bean 只在 `matrixcode.persistence.mode=jdbc` 时启用，避免默认 file 模式在本地启动、
     * 桌面演示或测试中误创建 H2 数据源。生产环境通过 `matrixcode.persistence.jdbc.*`
     * 指向 MySQL，MyBatis-Plus 复用同一个 DataSource。</p>
     */
    @Bean
    public DataSource matrixCodeDataSource(PersistenceModeProperties properties) {
        var jdbc = properties.getJdbc();
        if (jdbc.getUrl().isBlank()) {
            throw new IllegalStateException("JDBC 模式必须配置 matrixcode.persistence.jdbc.url");
        }
        var dataSource = new DriverManagerDataSource();
        dataSource.setUrl(jdbc.getUrl());
        dataSource.setUsername(jdbc.getUsername());
        dataSource.setPassword(jdbc.getPassword());
        return dataSource;
    }
}
