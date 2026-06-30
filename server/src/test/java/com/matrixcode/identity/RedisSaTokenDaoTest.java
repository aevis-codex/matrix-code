package com.matrixcode.identity;

import cn.dev33.satoken.dao.SaTokenDao;
import com.matrixcode.identity.application.RedisSaTokenDao;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisSaTokenDaoTest {

    @Test
    void 写入字符串时使用项目统一前缀和秒级过期时间() {
        var redis = redisTemplate();
        var dao = new RedisSaTokenDao(redis.template(), "matrixcode:sa-token:");

        dao.set("login:user-dev", "token-value", 300);

        verify(redis.values()).set("matrixcode:sa-token:login:user-dev", "token-value", Duration.ofSeconds(300));
    }

    @Test
    void 写入永久会话时不设置过期时间() {
        var redis = redisTemplate();
        var dao = new RedisSaTokenDao(redis.template(), "matrixcode:sa-token:");

        dao.set("login:user-dev", "token-value", SaTokenDao.NEVER_EXPIRE);

        verify(redis.values()).set("matrixcode:sa-token:login:user-dev", "token-value");
    }

    @Test
    void 更新已有键时保留剩余过期时间() {
        var redis = redisTemplate();
        when(redis.template().getExpire("matrixcode:sa-token:login:user-dev", TimeUnit.SECONDS)).thenReturn(120L);
        var dao = new RedisSaTokenDao(redis.template(), "matrixcode:sa-token:");

        dao.update("login:user-dev", "next-token");

        verify(redis.values()).set("matrixcode:sa-token:login:user-dev", "next-token", Duration.ofSeconds(120));
    }

    @Test
    void 更新缺失键时不创建新会话() {
        var redis = redisTemplate();
        when(redis.template().getExpire("matrixcode:sa-token:missing", TimeUnit.SECONDS)).thenReturn(SaTokenDao.NOT_VALUE_EXPIRE);
        var dao = new RedisSaTokenDao(redis.template(), "matrixcode:sa-token:");

        dao.update("missing", "next-token");

        verify(redis.values(), never()).set("matrixcode:sa-token:missing", "next-token");
    }

    @SuppressWarnings("unchecked")
    private RedisTemplateFixture redisTemplate() {
        var template = mock(StringRedisTemplate.class);
        var values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        return new RedisTemplateFixture(template, values);
    }

    private record RedisTemplateFixture(
            StringRedisTemplate template,
            ValueOperations<String, String> values
    ) {
    }
}
