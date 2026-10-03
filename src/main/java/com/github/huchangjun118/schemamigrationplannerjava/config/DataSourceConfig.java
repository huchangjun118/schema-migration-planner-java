package com.github.huchangjun118.schemamigrationplannerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 双数据源配置：
 * <ul>
 *   <li>metadataDataSource：承载版本、计划、步骤状态、回填进度、租约、审计等元数据（本地嵌入）</li>
 *   <li>targetDataSource：被迁移的目标库，所有 DDL/回填都作用于它（本地嵌入）</li>
 * </ul>
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "migration.metadata")
    public DataSource metadataDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    @ConfigurationProperties(prefix = "migration.target")
    public DataSource targetDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    public JdbcTemplate metadataJdbcTemplate(DataSource metadataDataSource) {
        return new JdbcTemplate(metadataDataSource);
    }

    @Bean
    public JdbcTemplate targetJdbcTemplate(DataSource targetDataSource) {
        return new JdbcTemplate(targetDataSource);
    }
}
