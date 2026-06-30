# MatrixCode 生产健康探测与告警

## 范围

本手册覆盖单机部署下的主动健康探测。探测脚本读取生产环境文件，访问 `/actuator/health`，当响应不是 `UP` 或请求失败时返回非零退出码，并在配置 webhook 时发送低敏 JSON 告警。

## 配置

生产环境文件中可配置：

- `MATRIXCODE_HEALTH_PROBE_URL`：默认 `http://127.0.0.1:8080/actuator/health`。
- `MATRIXCODE_HEALTH_PROBE_TIMEOUT_SECONDS`：默认 `5`。
- `MATRIXCODE_ALERT_WEBHOOK_URL`：可选 webhook 地址，不配置时只输出失败日志。
- `MATRIXCODE_ALERT_WEBHOOK_SECRET`：可选 HMAC 签名密钥；配置后请求会带 `X-MatrixCode-Alert-Signature: sha256=...`。
- `MATRIXCODE_ALERT_WEBHOOK_FORMAT`：告警平台格式，默认 `matrixcode`，支持 `matrixcode`、`dingtalk`、`wecom`、`feishu`、`slack`。
- `MATRIXCODE_ALERT_WEBHOOK_RETRIES`：告警发送重试次数，默认 `1`。
- `MATRIXCODE_ALERT_WEBHOOK_RETRY_DELAY_SECONDS`：告警重试间隔秒数，默认 `1`，可设为 `0`。
- `MATRIXCODE_ALERT_SEVERITY`：告警级别，支持 `P1`、`P2`、`P3`，默认 `P1`。
- `MATRIXCODE_ALERT_ESCALATION_OWNER`：当前值班或升级负责人，填值后进入告警正文。
- `MATRIXCODE_ALERT_RUNBOOK_URL`：故障处理手册地址，填值后进入告警正文。
- `MATRIXCODE_ALERT_SILENCE_UNTIL_EPOCH_SECONDS`：告警静默截止时间，Unix epoch 秒；默认 `0` 表示不静默。
- `MATRIXCODE_ALERT_SILENCE_REASON`：静默原因，建议填写发布、回滚或维护窗口编号。

脚本仍要求 `MATRIXCODE_PRODUCTION_CHECK=true`，避免误用开发配置作为生产探测。

## dry-run

```bash
MATRIXCODE_HEALTH_PROBE_DRY_RUN=true /opt/matrixcode/scripts/probe-production-health.sh /etc/matrixcode/matrixcode.env
```

dry-run 只校验配置并输出目标 URL，不发起健康请求。

## 手动探测

```bash
/opt/matrixcode/scripts/probe-production-health.sh /etc/matrixcode/matrixcode.env
```

健康响应必须包含 `status=UP`。失败时脚本返回非零退出码，systemd timer 会记录失败状态。

默认 `matrixcode` payload 包含服务名、状态、严重级别、摘要、错误详情、值班人和手册 URL。钉钉、企业微信、飞书和 Slack 会转换为各自的 text 消息结构。所有格式都只发送低敏健康摘要，不包含数据库密码、API Key、Sa-Token、模型密钥或完整运行上下文。签名基于最终 JSON payload 生成，便于告警网关侧校验来源。

常见平台配置示例：

```bash
MATRIXCODE_ALERT_WEBHOOK_URL=https://open.feishu.cn/open-apis/bot/v2/hook/replace-with-token
MATRIXCODE_ALERT_WEBHOOK_FORMAT=feishu
MATRIXCODE_ALERT_SEVERITY=P1
MATRIXCODE_ALERT_ESCALATION_OWNER=ops-primary
MATRIXCODE_ALERT_RUNBOOK_URL=https://runbook.example/matrixcode
```

发布、重启或回滚窗口内可临时设置静默：

```bash
MATRIXCODE_ALERT_SILENCE_UNTIL_EPOCH_SECONDS=1782748800
MATRIXCODE_ALERT_SILENCE_REASON=production-release
```

静默只跳过 webhook 发送，不改变健康探测失败结果。脚本仍返回非零退出码，systemd 日志仍会保留失败记录。静默结束后，失败探测会按 webhook 配置恢复发送告警。

## 健康快照

上线、重启或回滚后，使用健康快照脚本生成可归档的低敏 Markdown 报告：

```bash
/opt/matrixcode/scripts/capture-production-health-snapshot.sh /etc/matrixcode/matrixcode.env /var/log/matrixcode/health-snapshots/$(date +%Y%m%d%H%M%S).md
```

快照记录健康状态、info 入口可达性、Git 提交、env 文件 SHA256、Sa-Token 强制模式、Redis Session Store、RocketMQ 事件中继开关和 RocketMQ 协议门禁开关。报告不记录数据库密码、API Key、Sa-Token、模型密钥、完整 prompt、模型响应、向量正文或工具输出。健康状态不是 `UP` 时，脚本仍会写出快照并返回非零退出码。

## systemd timer

```bash
sudo cp /opt/matrixcode/ops/systemd/matrixcode-health-probe.service /etc/systemd/system/matrixcode-health-probe.service
sudo cp /opt/matrixcode/ops/systemd/matrixcode-health-probe.timer /etc/systemd/system/matrixcode-health-probe.timer
sudo systemctl daemon-reload
sudo systemctl enable --now matrixcode-health-probe.timer
systemctl list-timers matrixcode-health-probe.timer
```

查看最近探测日志：

```bash
journalctl -u matrixcode-health-probe.service -n 100 --no-pager
```

## 升级排班

上线前复制 `ops/alerting/matrixcode-escalation-schedule.example.md`，填写值班人、联系方式、升级路径和维护窗口。告警平台中的机器人、群组和排班系统应与该模板保持一致；发布、重启或回滚前如需静默，必须填写静默截止时间和原因。

## 验证命令

```bash
bash scripts/probe-production-health-test.sh
bash scripts/capture-production-health-snapshot-test.sh
```
