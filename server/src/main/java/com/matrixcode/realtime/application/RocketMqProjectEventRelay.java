package com.matrixcode.realtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.realtime.domain.ProjectEvent;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 基于 RocketMQ 的项目事件跨节点中继。
 *
 * <p>该组件只在 `matrixcode.rocketmq.event-relay.enabled=true` 时注册。它负责把本机
 * ProjectEvent 写入 RocketMQ，并消费同一 Topic 上其他实例发布的 ProjectEvent，再交给
 * ProjectEventBus 做本机持久化、去重和 SSE 推送。</p>
 */
@Component
@ConditionalOnProperty(prefix = "matrixcode.rocketmq.event-relay", name = "enabled", havingValue = "true")
public class RocketMqProjectEventRelay implements ProjectEventRelay, SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(RocketMqProjectEventRelay.class);

    private final RocketMqProperties properties;
    private final ProjectEventRocketMqCodec codec;
    private final DefaultMQProducer producer;
    private final DefaultMQPushConsumer consumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Consumer<ProjectEvent> subscriber = ignored -> {
    };

    public RocketMqProjectEventRelay(RocketMqProperties properties, ObjectMapper objectMapper) {
        this(
                properties,
                new ProjectEventRocketMqCodec(objectMapper),
                new DefaultMQProducer(properties.eventRelayProducerGroup()),
                new DefaultMQPushConsumer(properties.eventRelayConsumerGroup())
        );
    }

    RocketMqProjectEventRelay(
            RocketMqProperties properties,
            ProjectEventRocketMqCodec codec,
            DefaultMQProducer producer,
            DefaultMQPushConsumer consumer
    ) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
        this.producer = Objects.requireNonNull(producer, "producer 不能为空");
        this.consumer = Objects.requireNonNull(consumer, "consumer 不能为空");
    }

    /**
     * 将本机项目事件发送到 RocketMQ。
     */
    @Override
    public void publish(ProjectEvent event) {
        if (!running.get()) {
            throw new IllegalStateException("RocketMQ 项目事件中继尚未启动");
        }
        try {
            producer.send(codec.toMessage(event, properties), properties.getEventRelay().getSendTimeoutMs());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RocketMQ 项目事件发送被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("RocketMQ 项目事件发送失败", exception);
        }
    }

    /**
     * 注册 ProjectEventBus 的远端事件入口。
     */
    @Override
    public AutoCloseable subscribe(Consumer<ProjectEvent> subscriber) {
        this.subscriber = Objects.requireNonNull(subscriber, "subscriber 不能为空");
        return () -> this.subscriber = ignored -> {
        };
    }

    /**
     * 启动 RocketMQ 生产者和消费者。
     */
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            configureProducer();
            configureConsumer();
            producer.start();
            consumer.start();
            LOGGER.info(
                    "RocketMQ 项目事件中继已启动 nameServer={} topic={} producerGroup={} consumerGroup={}",
                    properties.getNameServer(),
                    properties.eventRelayTopic(),
                    properties.eventRelayProducerGroup(),
                    properties.eventRelayConsumerGroup()
            );
        } catch (Exception exception) {
            running.set(false);
            shutdownQuietly();
            throw new IllegalStateException("RocketMQ 项目事件中继启动失败", exception);
        }
    }

    /**
     * 停止 RocketMQ 生产者和消费者。
     */
    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            shutdownQuietly();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return 0;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private void configureProducer() {
        producer.setNamesrvAddr(properties.getNameServer());
        producer.setInstanceName("matrixcode-project-event-producer");
        producer.setSendMsgTimeout(properties.getEventRelay().getSendTimeoutMs());
        producer.setVipChannelEnabled(false);
    }

    private void configureConsumer() throws Exception {
        consumer.setNamesrvAddr(properties.getNameServer());
        consumer.setInstanceName("matrixcode-project-event-consumer");
        consumer.setConsumeThreadMin(properties.getEventRelay().getConsumeThreadMin());
        consumer.setConsumeThreadMax(properties.getEventRelay().getConsumeThreadMax());
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.subscribe(properties.eventRelayTopic(), properties.getEventRelay().getTag());
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            try {
                for (var message : messages) {
                    subscriber.accept(codec.fromBody(message.getBody()));
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (RuntimeException exception) {
                LOGGER.warn("RocketMQ 项目事件消费失败：{}", exception.getMessage(), exception);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });
    }

    private void shutdownQuietly() {
        try {
            consumer.shutdown();
        } catch (RuntimeException exception) {
            LOGGER.warn("RocketMQ 项目事件消费者关闭失败：{}", exception.getMessage(), exception);
        }
        try {
            producer.shutdown();
        } catch (RuntimeException exception) {
            LOGGER.warn("RocketMQ 项目事件生产者关闭失败：{}", exception.getMessage(), exception);
        }
    }
}
