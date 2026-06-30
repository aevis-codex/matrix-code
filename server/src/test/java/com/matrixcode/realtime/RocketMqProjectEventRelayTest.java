package com.matrixcode.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.realtime.application.ProjectEventRocketMqCodec;
import com.matrixcode.realtime.application.RocketMqProperties;
import com.matrixcode.realtime.domain.ProjectEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMqProjectEventRelayTest {

    @Test
    void 项目事件编码为RocketMq消息时只包含低敏业务字段() {
        var properties = new RocketMqProperties();
        properties.setTopicPrefix("matrixcode");
        properties.getEventRelay().setTopicSuffix("project-events");
        properties.getEventRelay().setTag("project-event");
        var codec = new ProjectEventRocketMqCodec(new ObjectMapper());
        var event = new ProjectEvent(
                "event-1",
                "project-1",
                "AGENT_REPORTED",
                "开发智能体已接收任务",
                Instant.parse("2026-06-29T10:00:00Z"),
                "DEVELOPER",
                "agent-run-1"
        );

        var message = codec.toMessage(event, properties);
        var decoded = codec.fromBody(message.getBody());

        assertThat(message.getTopic()).isEqualTo("matrixcode-project-events");
        assertThat(message.getTags()).isEqualTo("project-event");
        assertThat(message.getKeys()).isEqualTo("event-1");
        assertThat(message.getUserProperty("projectId")).isEqualTo("project-1");
        assertThat(message.getUserProperty("eventType")).isEqualTo("AGENT_REPORTED");
        assertThat(decoded).isEqualTo(event);
        assertThat(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("apiKey", "password", "secret", "token");
    }

    @Test
    void RocketMq配置按项目命名空间生成Topic和消费组() {
        var properties = new RocketMqProperties();
        properties.setTopicPrefix("matrixcode");
        properties.getEventRelay().setProducerGroupSuffix("project-event-producer");
        properties.getEventRelay().setConsumerGroupSuffix("project-event-consumer");

        assertThat(properties.eventRelayTopic()).isEqualTo("matrixcode-project-events");
        assertThat(properties.eventRelayProducerGroup()).isEqualTo("matrixcode-project-event-producer");
        assertThat(properties.eventRelayConsumerGroup()).isEqualTo("matrixcode-project-event-consumer");
    }
}
