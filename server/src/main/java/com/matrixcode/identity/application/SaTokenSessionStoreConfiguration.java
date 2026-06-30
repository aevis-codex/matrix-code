package com.matrixcode.identity.application;

import cn.dev33.satoken.dao.SaTokenDao;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class SaTokenSessionStoreConfiguration {

    /**
     * 在生产环境显式开启 Redis Session 时，将 Sa-Token 的会话 DAO 切换为 Redis。
     *
     * <p>默认保持内存 DAO，避免本地测试和离线开发在未配置 Redis 时启动失败。上线部署设置
     * `MATRIXCODE_AUTH_SESSION_STORE=redis` 后，登录 token、会话对象和临时数据都会通过
     * Sa-Token DAO 写入 Redis。</p>
     *
     * @param redisTemplate Spring Boot 创建的 Redis 字符串模板。
     * @param properties    MatrixCode 认证配置。
     * @return Sa-Token Redis DAO。
     */
    @Bean
    @ConditionalOnProperty(prefix = "matrixcode.auth", name = "session-store", havingValue = "redis")
    public SaTokenDao redisSaTokenDao(StringRedisTemplate redisTemplate, MatrixCodeAuthProperties properties) {
        return new RedisSaTokenDao(redisTemplate, properties.getSessionRedisKeyPrefix());
    }
}
