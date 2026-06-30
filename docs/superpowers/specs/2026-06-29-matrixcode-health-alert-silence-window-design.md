# MatrixCode 生产健康告警静默窗口设计

## 背景

第 132 阶段已为健康探测告警补齐签名与重试，第 136、137 阶段补齐重启和回滚门禁。发布、重启或回滚期间，短时间健康失败属于预期风险，如果继续发送 webhook，容易造成告警风暴和误升级。

## 目标

- 支持按 Unix epoch 秒配置告警静默截止时间。
- 静默窗口内健康失败仍返回非零退出码，保留 systemd 失败记录。
- 静默窗口内不发送 webhook。
- 静默原因进入脚本输出，方便运维日志回溯。
- 配置进入生产 env 示例和部署资产门禁。

## 非目标

- 不接入企业微信、飞书、钉钉或 Alertmanager 的平台级静默 API。
- 不改变健康检查成功/失败判定。
- 不保存真实 webhook 或告警密钥。

## 方案

新增配置：

- `MATRIXCODE_ALERT_SILENCE_UNTIL_EPOCH_SECONDS`：静默截止时间，默认 `0`。
- `MATRIXCODE_ALERT_SILENCE_REASON`：静默原因，默认空。
- `MATRIXCODE_ALERT_NOW_EPOCH_SECONDS`：当前时间覆盖值，仅用于脚本测试和可重复验证。

`probe-production-health.sh` 在发送 webhook 前判断当前时间是否小于静默截止时间：

- 若处于静默窗口，输出静默原因并跳过 webhook。
- 若静默已过期，按原有签名与重试逻辑发送 webhook。
- 无论是否静默，健康失败仍返回非零退出码。

## 验证

```bash
bash scripts/probe-production-health-test.sh
bash scripts/verify-production-deployment-assets.sh
bash scripts/verify-production-readiness.sh
git diff --check
```

## 回溯

- 与上线安全目标一致：发布、回滚期间可降低告警噪音，但不会隐藏健康失败。
- 与第 132 阶段一致：静默只包裹 webhook 发送，签名与重试逻辑保持不变。
- 与第 136、137 阶段一致：重启和回滚仍可显式执行，静默只是运维窗口控制，不替代回滚或恢复。
