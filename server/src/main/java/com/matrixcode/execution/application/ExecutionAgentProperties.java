package com.matrixcode.execution.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "matrixcode.execution-agents")
public class ExecutionAgentProperties {

    private String sharedSecret = "";
    private List<String> previousSharedSecrets = List.of();
    private long heartbeatTtlSeconds = 120;

    /**
     * 返回执行代理共享密钥。
     *
     * <p>该值只能来自环境变量或部署密钥系统；为空时保持本地开发兼容，不启用代理设备凭据校验。</p>
     */
    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret.trim();
    }

    /**
     * 返回执行代理轮换期间允许的旧共享密钥。
     *
     * <p>旧密钥只用于短期滚动升级代理进程；生产环境仍必须配置当前 `sharedSecret`，并在代理全部更新后清空旧密钥列表。</p>
     */
    public List<String> getPreviousSharedSecrets() {
        return previousSharedSecrets;
    }

    public void setPreviousSharedSecrets(List<String> previousSharedSecrets) {
        if (previousSharedSecrets == null) {
            this.previousSharedSecrets = List.of();
            return;
        }
        this.previousSharedSecrets = previousSharedSecrets.stream()
                .filter(secret -> secret != null && !secret.isBlank())
                .map(String::trim)
                .toList();
    }

    public long getHeartbeatTtlSeconds() {
        return heartbeatTtlSeconds;
    }

    public void setHeartbeatTtlSeconds(long heartbeatTtlSeconds) {
        this.heartbeatTtlSeconds = heartbeatTtlSeconds < 1 ? 120 : heartbeatTtlSeconds;
    }

    /**
     * 返回已认证心跳可用于结果上报的有效期。
     */
    public Duration heartbeatTtl() {
        return Duration.ofSeconds(heartbeatTtlSeconds);
    }

    /**
     * 汇总当前密钥和轮换旧密钥，供控制器统一校验代理回调。
     */
    public List<String> acceptedSharedSecrets() {
        var acceptedSecrets = new ArrayList<String>();
        if (!sharedSecret.isBlank()) {
            acceptedSecrets.add(sharedSecret);
        }
        acceptedSecrets.addAll(previousSharedSecrets);
        return List.copyOf(acceptedSecrets);
    }
}
