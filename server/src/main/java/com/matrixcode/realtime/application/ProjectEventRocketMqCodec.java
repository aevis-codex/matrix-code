package com.matrixcode.realtime.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.realtime.domain.ProjectEvent;
import org.apache.rocketmq.common.message.Message;

import java.io.IOException;

/**
 * ProjectEvent 与 RocketMQ Message 之间的编解码器。
 *
 * <p>消息体仅序列化 `ProjectEvent` 领域字段；RocketMQ 属性只写入项目 ID 和事件类型，
 * 便于排查消息流向，不携带任何凭证、Token 或数据库连接信息。</p>
 */
public class ProjectEventRocketMqCodec {

    private final ObjectMapper objectMapper;

    public ProjectEventRocketMqCodec(ObjectMapper objectMapper) {
        this.objectMapper = (objectMapper == null ? new ObjectMapper() : objectMapper.copy()).findAndRegisterModules();
    }

    /**
     * 将项目事件编码为 RocketMQ 消息。
     */
    public Message toMessage(ProjectEvent event, RocketMqProperties properties) {
        var message = new Message(
                properties.eventRelayTopic(),
                properties.getEventRelay().getTag(),
                event.id(),
                toBody(event)
        );
        message.putUserProperty("projectId", event.projectId());
        message.putUserProperty("eventType", event.type());
        return message;
    }

    /**
     * 从 RocketMQ 消息体反序列化项目事件。
     */
    public ProjectEvent fromBody(byte[] body) {
        try {
            return objectMapper.readValue(body, ProjectEvent.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("RocketMQ 项目事件消息体无法解析", exception);
        }
    }

    private byte[] toBody(ProjectEvent event) {
        try {
            return objectMapper.writeValueAsBytes(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("RocketMQ 项目事件消息体无法序列化", exception);
        }
    }
}
