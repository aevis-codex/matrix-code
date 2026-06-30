# MatrixCode 生产健康告警签名与重试设计

## 背景

第 125 阶段已经提供生产健康探测脚本和基础 webhook 告警，但告警发送只有一次尝试，且缺少平台网关常见的签名校验能力。真实上线后，如果告警网关短暂抖动，单次发送容易丢告警；如果 webhook 暴露到外部，也需要最小来源校验。

第 132 阶段补齐健康告警重试和 HMAC 签名，保持 payload 低敏，不引入具体厂商 SDK。

## 目标

- `probe-production-health.sh` 支持告警 webhook 重试次数和重试间隔。
- 配置 `MATRIXCODE_ALERT_WEBHOOK_SECRET` 后，告警请求带 `X-MatrixCode-Alert-Signature: sha256=...`。
- dry-run 和配置校验能发现非法重试配置。
- 告警 payload 仍只包含服务名、状态、摘要和错误详情，不包含密钥或完整运行上下文。

## 配置

- `MATRIXCODE_ALERT_WEBHOOK_URL`：可选 webhook 地址。
- `MATRIXCODE_ALERT_WEBHOOK_SECRET`：可选 HMAC 签名密钥。
- `MATRIXCODE_ALERT_WEBHOOK_RETRIES`：告警发送重试次数，默认 `1`。
- `MATRIXCODE_ALERT_WEBHOOK_RETRY_DELAY_SECONDS`：重试间隔秒数，默认 `1`。

## 验证标准

- TDD 红灯：模拟 webhook 第一次失败、第二次成功时，旧脚本只发送 1 次。
- 绿灯：相同场景发送 2 次并返回健康探测失败状态。
- 绿灯：签名密钥存在时，请求头包含 `X-MatrixCode-Alert-Signature: sha256=...`。
- 生产就绪聚合门禁继续通过。

## 回溯对齐

- 对齐真实可上线目标：健康探测失败能更可靠地进入告警平台。
- 对齐安全要求：签名只基于低敏 payload 生成，仓库不保存真实 webhook 地址或签名密钥。
- 对齐运维边界：脚本仍不自动重启服务，不执行回滚；失败处置继续由值守流程或后续审批链路处理。
