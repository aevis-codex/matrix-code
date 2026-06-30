# MatrixCode 第 143 阶段设计：告警平台适配与升级排班

## 背景

第 125、132、138 阶段已经补齐生产健康探测、webhook 签名重试和静默窗口。当前剩余缺口是告警 payload 仍只有 MatrixCode 自定义 JSON，接入飞书、钉钉、企业微信或 Slack 时需要额外网关转换；运维升级排班也只停留在文字待办，没有随发布资产一起交付。

## 推荐方案

在 `probe-production-health.sh` 中新增告警平台格式适配，保持默认 `matrixcode` 自定义 JSON 不变。生产环境通过 `MATRIXCODE_ALERT_WEBHOOK_FORMAT` 选择 `matrixcode`、`dingtalk`、`wecom`、`feishu` 或 `slack`。脚本仍只发送低敏摘要，不保存 token、数据库密码、模型密钥、Sa-Token 或完整日志。

同时新增告警升级排班模板，覆盖 P1/P2/P3 响应口径、值班人、升级路径、维护窗口和静默规则。模板作为运维资产随发布包校验，不引入数据库表或平台内配置。

## 配置

- `MATRIXCODE_ALERT_WEBHOOK_FORMAT`：默认 `matrixcode`。
- `MATRIXCODE_ALERT_SEVERITY`：默认 `P1`。
- `MATRIXCODE_ALERT_ESCALATION_OWNER`：默认空，填值后进入告警正文。
- `MATRIXCODE_ALERT_RUNBOOK_URL`：默认空，填值后进入告警正文。

## 验收标准

- 旧默认 JSON payload 行为不变。
- 钉钉/企业微信使用 `msgtype=text`，飞书使用 `msg_type=text`，Slack 使用 `text`。
- 不支持的格式会在配置校验阶段失败。
- 告警正文包含服务、状态、严重级别、摘要、详情、值班人和手册 URL。
- 发布资产验证覆盖环境变量、脚本格式分支、告警手册和排班模板。

## 回溯

- 对齐最初上线目标：真实生产部署需要故障可发现、可分派、可升级。
- 对齐安全边界：webhook URL 和 secret 仍只来自环境文件，不进入仓库；payload 只含低敏健康摘要。
- 对齐阶段闭环：第 138 阶段静默窗口仍只跳过发送，不改变健康探测失败结果。
