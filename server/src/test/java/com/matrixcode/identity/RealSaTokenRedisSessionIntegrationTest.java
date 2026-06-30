package com.matrixcode.identity;

import cn.dev33.satoken.dao.SaTokenDao;
import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.identity.application.RedisSaTokenDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "matrixcode.real-runtime-test", matches = "true")
class RealSaTokenRedisSessionIntegrationTest {

    @Test
    void 真实Redis可承载SaToken会话数据() {
        var keyPrefix = "matrixcode:test:sa-token:" + UUID.randomUUID() + ":";
        var key = "login:" + UUID.randomUUID();
        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of("spring.main.banner-mode", "off"))
                .run(
                        "--matrixcode.auth.session-store=redis",
                        "--matrixcode.auth.session-redis-key-prefix=" + keyPrefix,
                        "--matrixcode.persistence.mode=file",
                        "--matrixcode.persistence.jdbc.migrate-on-startup=false"
                )) {
            var dao = context.getBean(SaTokenDao.class);

            assertThat(dao).isInstanceOf(RedisSaTokenDao.class);

            dao.set(key, "token-value", 60);

            assertThat(dao.get(key)).isEqualTo("token-value");
            assertThat(dao.getTimeout(key)).isBetween(1L, 60L);

            dao.delete(key);

            assertThat(dao.get(key)).isNull();
            assertThat(dao.getTimeout(key)).isEqualTo(SaTokenDao.NOT_VALUE_EXPIRE);
        }
    }
}
