# MatrixCode RocketMQ 项目事件中继设计

## 背景

MatrixCode 已经通过 `ProjectEventBus` 支持本机项目事件持久化和 SSE 推送，但多人实时协作在多实例部署下还需要跨节点事件分发。原有 RocketMQ 配置只做端口连通性诊断，不能保证项目事件能在不同服务实例之间传播。

## 目标

- 保留单实例本机事件流的现有行为。
- 增加 `ProjectEventRelay` 中继接口，避免 ProjectEventBus 直接依赖具体消息队列。
- 本地发布的项目事件先落本机状态，再尽力广播到中继。
- 远端事件入站后只落本机和通知订阅者，不再回发到中继。
- 按事件 ID 去重，避免 MQ 回投或重复投递造成 SSE 重复事件。
- 提供 RocketMQ 真实适配器，显式开启后使用 `DefaultMQProducer` 和 `DefaultMQPushConsumer` 收发低敏 `ProjectEvent` 消息。
- 提供真实协议级收发门禁，区分「NameServer TCP 可达」和「Topic 可真实收发」。

## 非目标

- 本阶段不自动创建或修改 RocketMQ broker 配置。
- 本阶段不把 API Key、数据库密码、Token 等敏感信息写入 MQ 消息体或消息属性。
- 本阶段不替换现有 MySQL 项目事件持久化。

## 配置

```yaml
matrixcode:
  rocketmq:
    name-server: ${MATRIXCODE_ROCKETMQ_NAME_SERVER:127.0.0.1:9876}
    topic-prefix: ${MATRIXCODE_ROCKETMQ_TOPIC_PREFIX:matrixcode}
    event-relay:
      enabled: ${MATRIXCODE_ROCKETMQ_EVENT_RELAY_ENABLED:false}
      topic-suffix: ${MATRIXCODE_ROCKETMQ_EVENT_RELAY_TOPIC_SUFFIX:project-events}
      tag: ${MATRIXCODE_ROCKETMQ_EVENT_RELAY_TAG:project-event}
      producer-group-suffix: ${MATRIXCODE_ROCKETMQ_EVENT_RELAY_PRODUCER_GROUP_SUFFIX:project-event-producer}
      consumer-group-suffix: ${MATRIXCODE_ROCKETMQ_EVENT_RELAY_CONSUMER_GROUP_SUFFIX:project-event-consumer}
      send-timeout-ms: ${MATRIXCODE_ROCKETMQ_EVENT_RELAY_SEND_TIMEOUT_MS:3000}
```

生产上线前如需验证真实收发，打开：

```bash
MATRIXCODE_PROTOCOL_CHECK=true
MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK=true
```

## 验证

- 红灯：`ProjectEventStreamTest#本地发布项目事件后会进入跨节点中继且远端事件只在本机落地一次` 在缺少 `ProjectEventRelay` 时编译失败。
- 绿灯：该测试通过，证明本机发布进入中继、远端事件入站、不回环且去重。
- `RocketMqProjectEventRelayTest` 验证 RocketMQ 消息 topic、tag、key、属性和 JSON 低敏载荷。
- `RealRuntimeIntegrationTest#真实RocketMq项目事件Topic可收发低敏消息` 可用于真实 broker 协议级收发验收。

## 当前真实环境发现

2026-06-29 在当前环境验证时：

- `127.0.0.1:9876` NameServer TCP 可达。
- `127.0.0.1:10911` broker 端口 TCP 可达。
- RocketMQ Java 客户端向 `matrixcode-project-events` 发送低敏 `ProjectEvent` 时超时，日志表现为 `sendDefaultImpl call timeout`。
- 5.3.2 和 4.9.8 客户端均能拿到路由，但 broker remoting 请求被关闭或超时。

该现象说明 broker 对外 remoting 层仍未达到项目事件中继可上线标准。后续需要在 RocketMQ broker 侧检查 `brokerIP1`、容器网络、10911 端口暴露、防火墙、代理和 Topic 权限；修复后使用 `MATRIXCODE_ROCKETMQ_PROTOCOL_CHECK=true` 复验。
