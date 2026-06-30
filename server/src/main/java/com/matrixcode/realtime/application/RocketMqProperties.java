package com.matrixcode.realtime.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 连接和项目事件中继配置。
 *
 * <p>生产环境通过 `matrixcode.rocketmq.*` 绑定真实 NameServer、Topic 和消费组。
 * 事件中继默认关闭，只有显式开启后才会创建 RocketMQ 生产者和消费者。</p>
 */
@Component
@ConfigurationProperties(prefix = "matrixcode.rocketmq")
public class RocketMqProperties {

    private String nameServer = "127.0.0.1:9876";
    private String topicPrefix = "matrixcode";
    private EventRelay eventRelay = new EventRelay();

    public String getNameServer() {
        return nameServer;
    }

    public void setNameServer(String nameServer) {
        this.nameServer = textOr(nameServer, "127.0.0.1:9876");
    }

    public String getTopicPrefix() {
        return topicPrefix;
    }

    public void setTopicPrefix(String topicPrefix) {
        this.topicPrefix = textOr(topicPrefix, "matrixcode");
    }

    public EventRelay getEventRelay() {
        return eventRelay;
    }

    public void setEventRelay(EventRelay eventRelay) {
        this.eventRelay = eventRelay == null ? new EventRelay() : eventRelay;
    }

    /**
     * 返回项目事件中继 Topic 名称。
     */
    public String eventRelayTopic() {
        return topicPrefix + "-" + eventRelay.topicSuffix;
    }

    /**
     * 返回项目事件中继生产者组名称。
     */
    public String eventRelayProducerGroup() {
        return topicPrefix + "-" + eventRelay.producerGroupSuffix;
    }

    /**
     * 返回项目事件中继消费者组名称。
     */
    public String eventRelayConsumerGroup() {
        return topicPrefix + "-" + eventRelay.consumerGroupSuffix;
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static class EventRelay {
        private boolean enabled;
        private String topicSuffix = "project-events";
        private String tag = "project-event";
        private String producerGroupSuffix = "project-event-producer";
        private String consumerGroupSuffix = "project-event-consumer";
        private int sendTimeoutMs = 3000;
        private int consumeThreadMin = 2;
        private int consumeThreadMax = 4;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTopicSuffix() {
            return topicSuffix;
        }

        public void setTopicSuffix(String topicSuffix) {
            this.topicSuffix = textOr(topicSuffix, "project-events");
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = textOr(tag, "project-event");
        }

        public String getProducerGroupSuffix() {
            return producerGroupSuffix;
        }

        public void setProducerGroupSuffix(String producerGroupSuffix) {
            this.producerGroupSuffix = textOr(producerGroupSuffix, "project-event-producer");
        }

        public String getConsumerGroupSuffix() {
            return consumerGroupSuffix;
        }

        public void setConsumerGroupSuffix(String consumerGroupSuffix) {
            this.consumerGroupSuffix = textOr(consumerGroupSuffix, "project-event-consumer");
        }

        public int getSendTimeoutMs() {
            return sendTimeoutMs;
        }

        public void setSendTimeoutMs(int sendTimeoutMs) {
            this.sendTimeoutMs = sendTimeoutMs <= 0 ? 3000 : sendTimeoutMs;
        }

        public int getConsumeThreadMin() {
            return consumeThreadMin;
        }

        public void setConsumeThreadMin(int consumeThreadMin) {
            this.consumeThreadMin = consumeThreadMin <= 0 ? 2 : consumeThreadMin;
        }

        public int getConsumeThreadMax() {
            return consumeThreadMax;
        }

        public void setConsumeThreadMax(int consumeThreadMax) {
            this.consumeThreadMax = consumeThreadMax <= 0 ? 4 : consumeThreadMax;
        }

        private String textOr(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
