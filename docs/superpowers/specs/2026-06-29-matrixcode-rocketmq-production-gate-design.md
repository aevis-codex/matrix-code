# MatrixCode 第 163 阶段：RocketMQ 生产中继门禁设计

## 目标

修正生产配置口径：RocketMQ 项目事件中继不能因为端口可达就默认启用；只有真实 topic 收发门禁开启并通过时，才允许生产开启中继。

## 决策

- `ops/env/matrixcode.env.example` 将 `MATRIXCODE_ROCKETMQ_EVENT_RELAY_ENABLED` 默认改为 `false`。
- `scripts/check-real-runtime.sh` 在 `MATRIXCODE_PRODUCTION_CHECK=true` 且 `MATRIXCODE_ROCKETMQ_EVENT_RELAY_ENABLED=true` 时强制要求：
  - `MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK=true`
  - `MATRIXCODE_ROCKETMQ_NAME_SERVER` 非空
  - `MATRIXCODE_ROCKETMQ_TOPIC_PREFIX` 非空
  - `MATRIXCODE_ROCKETMQ_EVENT_RELAY_TOPIC_SUFFIX` 非空
  - `MATRIXCODE_ROCKETMQ_EVENT_RELAY_TAG` 非空
- 生产运行手册和本地运行文档明确：单实例上线默认关闭 MQ 中继；多实例开启前必须先跑真实 RocketMQ 协议门禁。

## 验证

- 红灯：`bash scripts/check-real-runtime-production-test.sh` 先确认旧门禁会误放行“中继开启但协议门禁关闭”的生产配置。
- 绿灯：
  - `bash scripts/check-real-runtime-production-test.sh`
  - `bash scripts/verify-production-readiness.sh`
  - `MATRIXCODE_PROTOCOL_CHECK=true bash scripts/check-real-runtime.sh .env.local`

## 与最初需求对齐

- 对齐“真实可上线运行”：当前 RocketMQ 真实 topic 发送仍有基础设施阻塞，生产默认关闭 MQ 中继更安全。
- 对齐多人实时协作目标：并未删除 RocketMQ 能力，只把多实例事件中继变成显式开启且必须通过协议门禁。
- 对齐回溯结论：第 159 阶段应用侧中继已完成，但基础设施未通过真实收发；本阶段把该风险固化为生产门禁。
