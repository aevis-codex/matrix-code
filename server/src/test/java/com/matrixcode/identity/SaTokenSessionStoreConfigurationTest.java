package com.matrixcode.identity;

import cn.dev33.satoken.dao.SaTokenDao;
import com.matrixcode.identity.application.MatrixCodeAuthProperties;
import com.matrixcode.identity.application.RedisSaTokenDao;
import com.matrixcode.identity.application.SaTokenSessionStoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SaTokenSessionStoreConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SaTokenSessionStoreConfiguration.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(MatrixCodeAuthProperties.class, MatrixCodeAuthProperties::new);

    @Test
    void 默认不启用Redis会话存储() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(SaTokenDao.class));
    }

    @Test
    void 显式配置Redis时启用SaTokenRedisDao() {
        contextRunner
                .withPropertyValues("matrixcode.auth.session-store=redis")
                .run(context -> assertThat(context.getBean(SaTokenDao.class)).isInstanceOf(RedisSaTokenDao.class));
    }
}
