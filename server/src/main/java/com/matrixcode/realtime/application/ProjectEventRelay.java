package com.matrixcode.realtime.application;

import com.matrixcode.realtime.domain.ProjectEvent;

import java.util.function.Consumer;

/**
 * 项目事件跨节点中继接口。
 *
 * <p>ProjectEventBus 负责本机落库和 SSE 通知；该接口负责把本机事件广播到其他服务实例，
 * 并把其他实例发布的事件送回本机总线。实现类必须保证消息体不包含 API Key、数据库密码等敏感信息。</p>
 */
public interface ProjectEventRelay {

    /**
     * 发布本机产生的项目事件，供其他 MatrixCode 服务实例消费。
     */
    void publish(ProjectEvent event);

    /**
     * 注册远端事件消费者。
     *
     * @return 可关闭订阅句柄，服务停止或测试清理时用于释放底层资源。
     */
    AutoCloseable subscribe(Consumer<ProjectEvent> subscriber);

    /**
     * 创建默认空实现，保持单实例部署和测试环境不依赖消息队列。
     */
    static ProjectEventRelay noop() {
        return new ProjectEventRelay() {
            @Override
            public void publish(ProjectEvent event) {
            }

            @Override
            public AutoCloseable subscribe(Consumer<ProjectEvent> subscriber) {
                return () -> {
                };
            }
        };
    }
}
