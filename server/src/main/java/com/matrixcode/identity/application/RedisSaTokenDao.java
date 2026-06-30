package com.matrixcode.identity.application;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.auto.SaTokenDaoByObjectFollowString;
import cn.dev33.satoken.util.SaFoxUtil;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class RedisSaTokenDao implements SaTokenDaoByObjectFollowString {

    private static final String DEFAULT_KEY_PREFIX = "matrixcode:sa-token:";

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public RedisSaTokenDao(StringRedisTemplate redisTemplate, String keyPrefix) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate 不能为空");
        this.keyPrefix = normalizeKeyPrefix(keyPrefix);
    }

    /**
     * 从 Redis 读取 Sa-Token 字符串值。
     *
     * @param key Sa-Token 逻辑键。
     * @return Redis 中保存的字符串值，缺失时返回 null。
     */
    @Override
    public String get(String key) {
        return redisTemplate.opsForValue().get(redisKey(key));
    }

    /**
     * 将 Sa-Token 字符串值写入 Redis，并按 Sa-Token 约定处理永久、缺失和秒级过期。
     *
     * @param key     Sa-Token 逻辑键。
     * @param value   需要保存的字符串值。
     * @param timeout 过期秒数，-1 表示永久，-2 或 0 表示不写入。
     */
    @Override
    public void set(String key, String value, long timeout) {
        setWithTimeout(redisKey(key), value, timeout);
    }

    /**
     * 更新已有 Sa-Token 键的值，同时保留 Redis 中剩余的 TTL。
     *
     * @param key   Sa-Token 逻辑键。
     * @param value 新的字符串值。
     */
    @Override
    public void update(String key, String value) {
        var timeout = getTimeout(key);
        if (timeout == SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        setWithTimeout(redisKey(key), value, timeout);
    }

    /**
     * 删除 Sa-Token 逻辑键对应的 Redis 数据。
     *
     * @param key Sa-Token 逻辑键。
     */
    @Override
    public void delete(String key) {
        redisTemplate.delete(redisKey(key));
    }

    /**
     * 查询 Sa-Token 逻辑键的剩余过期秒数。
     *
     * @param key Sa-Token 逻辑键。
     * @return -2 表示不存在，-1 表示永久，否则返回剩余秒数。
     */
    @Override
    public long getTimeout(String key) {
        var timeout = redisTemplate.getExpire(redisKey(key), TimeUnit.SECONDS);
        return timeout == null ? SaTokenDao.NOT_VALUE_EXPIRE : timeout;
    }

    /**
     * 更新 Sa-Token 逻辑键的过期时间。
     *
     * @param key     Sa-Token 逻辑键。
     * @param timeout 过期秒数，-1 表示永久，非正数表示删除。
     */
    @Override
    public void updateTimeout(String key, long timeout) {
        var redisKey = redisKey(key);
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            redisTemplate.persist(redisKey);
            return;
        }
        if (timeout <= 0) {
            redisTemplate.delete(redisKey);
            return;
        }
        redisTemplate.expire(redisKey, Duration.ofSeconds(timeout));
    }

    /**
     * 使用 Redis SCAN 查找 Sa-Token 逻辑键，避免在生产环境使用阻塞型 KEYS 命令。
     *
     * @param prefix   Sa-Token 查询前缀。
     * @param keyword  Sa-Token 查询关键字。
     * @param start    分页起点。
     * @param size     分页大小。
     * @param sortType 是否按正序排序。
     * @return 匹配的 Sa-Token 逻辑键列表，不包含项目 Redis 前缀。
     */
    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        var logicalKeys = new ArrayList<String>();
        var options = ScanOptions.scanOptions()
                .match(keyPrefix + "*")
                .count(1000)
                .build();
        try (var cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(redisKey -> logicalKeys.add(stripKeyPrefix(redisKey)));
        }
        return SaFoxUtil.searchList(logicalKeys, prefix, keyword, start, size, sortType);
    }

    private void setWithTimeout(String redisKey, String value, long timeout) {
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            redisTemplate.opsForValue().set(redisKey, value);
            return;
        }
        if (timeout <= SaTokenDao.NOT_VALUE_EXPIRE || timeout == 0) {
            return;
        }
        redisTemplate.opsForValue().set(redisKey, value, Duration.ofSeconds(timeout));
    }

    private String redisKey(String key) {
        return keyPrefix + key;
    }

    private String stripKeyPrefix(String redisKey) {
        return redisKey.startsWith(keyPrefix) ? redisKey.substring(keyPrefix.length()) : redisKey;
    }

    private String normalizeKeyPrefix(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            return DEFAULT_KEY_PREFIX;
        }
        var normalized = keyPrefix.trim();
        return normalized.endsWith(":") ? normalized : normalized + ":";
    }
}
