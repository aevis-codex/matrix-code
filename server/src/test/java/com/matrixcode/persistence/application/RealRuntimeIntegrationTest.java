package com.matrixcode.persistence.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.agentruntime.application.AgentRuntimeRepository;
import com.matrixcode.agentruntime.application.AgentRuntimeService;
import com.matrixcode.agentruntime.application.AgentRuntimeUserAuditService;
import com.matrixcode.agentruntime.application.AgentRuntimeWorkerModelExecutionService;
import com.matrixcode.agentruntime.application.AgentRuntimeWorkerService;
import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;
import com.matrixcode.agentruntime.domain.AgentRunStatus;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.modelgateway.application.MilvusVectorContextStore;
import com.matrixcode.modelgateway.application.ModelGatewayProperties;
import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.application.OpenAiCompatibleEmbeddingClient;
import com.matrixcode.modelgateway.application.OpenAiCompatibleModelClient;
import com.matrixcode.modelgateway.application.VectorContextEntry;
import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import com.matrixcode.modelgateway.domain.ModelRequestRecord;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.modelgateway.domain.PromptContract;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import com.matrixcode.realtime.application.ProjectEventRocketMqCodec;
import com.matrixcode.realtime.application.RocketMqProperties;
import com.matrixcode.realtime.domain.ProjectEvent;
import com.matrixcode.usage.domain.UsageRecord;
import com.matrixcode.workbench.application.ProjectActivityRepository;
import com.matrixcode.workbench.application.WorkbenchStateSnapshot;
import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowEventType;
import com.matrixcode.workflow.domain.WorkflowItem;
import com.matrixcode.workflow.domain.WorkflowState;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "matrixcode.real-runtime-test", matches = "true")
class RealRuntimeIntegrationTest {

    @Test
    void 真实Mysql可自动建库并执行Flyway迁移() throws Exception {
        var jdbcUrl = env("MATRIXCODE_PERSISTENCE_JDBC_URL");
        var username = env("MATRIXCODE_PERSISTENCE_JDBC_USERNAME");
        var password = env("MATRIXCODE_PERSISTENCE_JDBC_PASSWORD");

        DatabaseMigrator.migrate(jdbcUrl, username, password, true);

        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = connection.prepareStatement("""
                     select count(*)
                     from information_schema.tables
                     where table_schema = database()
                       and lower(table_name) in (
                         'flyway_schema_history',
                         'matrixcode_documents',
                         'matrixcode_bugs',
                         'matrixcode_deployment_targets',
                         'matrixcode_project_events',
                         'matrixcode_model_requests',
                         'matrixcode_workflow_items',
                         'matrixcode_workflow_events',
                         'matrixcode_acceptance_states',
                         'matrixcode_agent_runs',
                         'matrixcode_agent_run_events'
                     )
                     """);
            var resultSet = statement.executeQuery()) {
            resultSet.next();
            assertThat(resultSet.getInt(1)).isEqualTo(11);
            assertThat(nonIdPrimaryKeyTableCount(connection))
                    .as("真实 MySQL 所有 matrixcode_% 正式表必须使用 id 作为唯一主键")
                    .isZero();
        }

        var progressRepository = new JdbcWorkbenchProgressRepository(properties(jdbcUrl, username, password));
        var projectId = "real-progress-" + UUID.randomUUID();
        var itemId = "workflow-" + UUID.randomUUID();
        var occurredAt = Instant.parse("2026-06-25T12:00:00Z");
        var event = new WorkflowEvent(
                "workflow-event-" + UUID.randomUUID(),
                itemId,
                WorkflowEventType.SUBMIT_REVIEW,
                WorkflowState.DRAFT,
                WorkflowState.REVIEW_PENDING,
                "real-runtime",
                occurredAt
        );
        var acceptance = new WorkbenchStateSnapshot.AcceptanceState("acceptance-doc-" + UUID.randomUUID(), false, "测试");

        progressRepository.saveWorkflowItems(List.of(new WorkflowItem(itemId, projectId, "真实工作流验收", WorkflowState.REVIEW_PENDING)));
        progressRepository.saveWorkflowEvents(Map.of(itemId, List.of(event)));
        progressRepository.saveAcceptances(Map.of(projectId, acceptance));

        assertThat(progressRepository.loadWorkflowItems())
                .anySatisfy(item -> assertThat(item.id()).isEqualTo(itemId));
        assertThat(progressRepository.loadWorkflowEvents().get(itemId)).containsExactly(event);
        assertThat(progressRepository.loadAcceptances().get(projectId)).isEqualTo(acceptance);

        var identityRepository = new JdbcProjectIdentityRepository(properties(jdbcUrl, username, password));
        var userId = "real-user-" + UUID.randomUUID();
        var memberId = "real-member-" + UUID.randomUUID();
        var auditId = "real-audit-" + UUID.randomUUID();
        identityRepository.ensureUser(new MatrixUser(
                userId,
                userId,
                "真实用户验证",
                "",
                "ACTIVE",
                occurredAt,
                occurredAt
        ));
        identityRepository.ensureProject(projectId, "真实身份项目", userId, "真实身份验证");
        identityRepository.ensureMember(new ProjectMember(
                memberId,
                projectId,
                userId,
                "DEVELOPER",
                "ACTIVE",
                occurredAt,
                occurredAt,
                occurredAt
        ));
        insertRealAudit(jdbcUrl, username, password, auditId, projectId, userId, occurredAt);

        assertThat(identityRepository.members(projectId))
                .anySatisfy(member -> assertThat(member.userId()).isEqualTo(userId));
        assertThat(identityRepository.projectsForUser(userId)).contains(projectId);
        assertThat(identityRepository.auditRecords(projectId, userId))
                .anySatisfy(record -> assertThat(record.id()).isEqualTo(auditId));

        var agentRunId = "real-agent-run-" + UUID.randomUUID();
        var agentEventId = "real-agent-event-" + UUID.randomUUID();
        var stateDirectory = Files.createTempDirectory("matrixcode-real-runtime-");
        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of(
                        "matrixcode.workbench-state.storage-path", stateDirectory.resolve("workbench-state.json").toString(),
                        "matrixcode.local-execution.storage-path", stateDirectory.resolve("local-execution.json").toString(),
                        "matrixcode.runtime-notifications.storage-path", stateDirectory.resolve("runtime-notifications.json").toString(),
                        "spring.main.banner-mode", "off"
                ))
                .run(
                        "--matrixcode.persistence.mode=jdbc",
                        "--matrixcode.persistence.jdbc.url=" + jdbcUrl,
                        "--matrixcode.persistence.jdbc.username=" + username,
                        "--matrixcode.persistence.jdbc.password=" + password,
                        "--matrixcode.persistence.jdbc.migrate-on-startup=true"
                )) {
            var agentRuntimeRepository = context.getBean(AgentRuntimeRepository.class);
            var run = new AgentRunRecord(
                    agentRunId,
                    projectId,
                    "DEVELOPER",
                    "coding",
                    userId,
                    "deepseek",
                    "deepseek-chat",
                    AgentRunStatus.SUCCEEDED,
                    "真实 Agent 运行验证",
                    "真实 MyBatis-Plus 仓储写读通过",
                    occurredAt,
                    occurredAt,
                    occurredAt.plusSeconds(3),
                    occurredAt.plusSeconds(3)
            );
            var eventRecord = new AgentRunEventRecord(
                    agentEventId,
                    agentRunId,
                    projectId,
                    "RUN_SUCCEEDED",
                    "真实 Agent 运行完成",
                    "{\"result\":\"ok\"}",
                    occurredAt.plusSeconds(3)
            );

            agentRuntimeRepository.saveRun(run);
            agentRuntimeRepository.appendEvent(eventRecord);

            assertThat(agentRuntimeRepository.recentRuns(projectId, 5)).contains(run);
            assertThat(agentRuntimeRepository.eventsForRun(agentRunId)).containsExactly(eventRecord);

            var agentRuntimeService = context.getBean(AgentRuntimeService.class);
            var failedRun = agentRuntimeService.markFailed(
                    "real-agent-run-failed-" + UUID.randomUUID(),
                    projectId,
                    ModelRole.DEVELOPER,
                    "coding",
                    userId,
                    "deepseek",
                    "deepseek-chat",
                    "真实 Agent 恢复重试验证",
                    "真实 MySQL 恢复来源失败",
                    true,
                    null
            );
            var retryRun = agentRuntimeService.queueRetry(projectId, failedRun.id(), userId);
            var claimedRun = agentRuntimeService.claimQueuedRun(projectId, retryRun.id(), userId);
            var queuedNextRun = agentRuntimeService.queueRetry(projectId, failedRun.id(), userId);
            var claimedNextRun = agentRuntimeService.claimNextQueuedRun(projectId, userId);

            assertThat(retryRun.status()).isEqualTo(AgentRunStatus.QUEUED);
            assertThat(retryRun.retryOfRunId()).isEqualTo(failedRun.id());
            assertThat(claimedRun.status()).isEqualTo(AgentRunStatus.RUNNING);
            assertThat(claimedRun.retryOfRunId()).isEqualTo(failedRun.id());
            assertThat(claimedRun.claimedByUserId()).isEqualTo(userId);
            assertThat(claimedRun.claimExpiresAt()).isAfter(claimedRun.claimedAt());
            assertThat(queuedNextRun.status()).isEqualTo(AgentRunStatus.QUEUED);
            assertThat(claimedNextRun)
                    .hasValueSatisfying(nextRun -> {
                        assertThat(nextRun.id()).isEqualTo(queuedNextRun.id());
                        assertThat(nextRun.status()).isEqualTo(AgentRunStatus.RUNNING);
                        assertThat(nextRun.claimedByUserId()).isEqualTo(userId);
                        assertThat(nextRun.claimExpiresAt()).isAfter(nextRun.claimedAt());
                    });
            assertThat(agentRuntimeRepository.eventsForRun(failedRun.id()))
                    .extracting(AgentRunEventRecord::eventType)
                    .contains("RUN_FAILED", "RUN_RETRY_REQUESTED");
            assertThat(agentRuntimeRepository.eventsForRun(retryRun.id()))
                    .extracting(AgentRunEventRecord::eventType)
                    .containsExactly("RUN_RETRY_QUEUED", "RUN_CLAIMED", "RUN_STARTED", "RUN_LEASED");
            assertThat(agentRuntimeRepository.eventsForRun(queuedNextRun.id()))
                    .extracting(AgentRunEventRecord::eventType)
                    .containsExactly("RUN_RETRY_QUEUED", "RUN_CLAIMED", "RUN_STARTED", "RUN_LEASED");

            var renewedRun = agentRuntimeService.renewClaimLease(projectId, claimedNextRun.orElseThrow().id(), userId);
            assertThat(renewedRun)
                    .hasValueSatisfying(renewed -> assertThat(renewed.claimExpiresAt()).isAfter(renewed.claimedAt()));
            assertThat(agentRuntimeRepository.eventsForRun(claimedNextRun.orElseThrow().id()))
                    .extracting(AgentRunEventRecord::eventType)
                    .contains("RUN_LEASE_RENEWED");

            var expiredRunId = "real-agent-run-expired-" + UUID.randomUUID();
            agentRuntimeRepository.saveRun(new AgentRunRecord(
                    expiredRunId,
                    projectId,
                    "DEVELOPER",
                    "coding",
                    userId,
                    "deepseek",
                    "deepseek-chat",
                    AgentRunStatus.QUEUED,
                    "真实 Agent 过期租约验证",
                    "等待执行",
                    occurredAt.plusSeconds(20),
                    null,
                    null,
                    occurredAt.plusSeconds(20)
            ));
            var expiredClaim = agentRuntimeRepository.claimNextQueuedRun(
                    projectId,
                    userId,
                    occurredAt.plusSeconds(21),
                    occurredAt.plusSeconds(22)
            ).orElseThrow();
            assertThat(expiredClaim.id()).isEqualTo(expiredRunId);

            var tickQueuedRunId = "real-agent-run-worker-tick-" + UUID.randomUUID();
            agentRuntimeRepository.saveRun(new AgentRunRecord(
                    tickQueuedRunId,
                    projectId,
                    "DEVELOPER",
                    "coding",
                    userId,
                    "deepseek",
                    "deepseek-chat",
                    AgentRunStatus.QUEUED,
                    "真实 Agent Worker tick 验证",
                    "等待执行",
                    occurredAt.plusSeconds(30),
                    null,
                    null,
                    occurredAt.plusSeconds(30)
            ));
            var workerService = context.getBean(AgentRuntimeWorkerService.class);
            var tickResult = workerService.tick(projectId, userId);

            assertThat(tickResult.expiredRunCount()).isEqualTo(1);
            assertThat(tickResult.claimedRun()).isNotNull();
            assertThat(tickResult.claimedRun().id()).isEqualTo(tickQueuedRunId);
            assertThat(agentRuntimeRepository.findRun(expiredRunId))
                    .hasValueSatisfying(expired -> {
                        assertThat(expired.status()).isEqualTo(AgentRunStatus.FAILED);
                        assertThat(expired.retryable()).isTrue();
                    });
            assertThat(agentRuntimeRepository.eventsForRun(expiredRunId))
                    .extracting(AgentRunEventRecord::eventType)
                    .contains("RUN_LEASE_EXPIRED", "RUN_FAILED");
            assertThat(agentRuntimeRepository.eventsForRun(tickQueuedRunId))
                    .extracting(AgentRunEventRecord::eventType)
                    .contains("RUN_CLAIMED", "RUN_STARTED", "RUN_LEASED");

            var executionPlan = workerService.prepareExecution(projectId, tickQueuedRunId, userId);
            assertThat(executionPlan.executable()).isTrue();
            assertThat(executionPlan.steps()).hasSize(7);
            assertThat(executionPlan.steps().get(0).stepKey()).isEqualTo("CONTEXT_RECALL");
            assertThat(agentRuntimeRepository.eventsForRun(tickQueuedRunId))
                    .extracting(AgentRunEventRecord::eventType)
                    .contains("WORKER_EXECUTION_PREPARED");

            var modelExecutionService = context.getBean(AgentRuntimeWorkerModelExecutionService.class);
            var modelExecution = modelExecutionService.executeModelRequest(projectId, tickQueuedRunId, userId);
            assertThat(modelExecution.executed()).isTrue();
            assertThat(modelExecution.requestId()).isNotBlank();
            assertThat(modelExecution.answerSummary()).isNotBlank();
            assertThat(context.getBean(ProjectActivityRepository.class).loadModelRequests().get(projectId))
                    .anySatisfy(modelRequest -> {
                        assertThat(modelRequest.requestId()).isEqualTo(modelExecution.requestId());
                        assertThat(modelRequest.agentRunId()).isEqualTo(tickQueuedRunId);
                        assertThat(modelRequest.actorUserId()).isEqualTo(userId);
                    });
            assertThat(agentRuntimeRepository.eventsForRun(tickQueuedRunId))
                    .extracting(AgentRunEventRecord::eventType)
                    .contains("TOOL_TRACE", "WORKER_MODEL_REQUEST_COMPLETED");

            var userAuditService = context.getBean(AgentRuntimeUserAuditService.class);
            var userAudit = userAuditService.audit(projectId, userId, 20);
            assertThat(userAudit.entries())
                    .anySatisfy(entry -> {
                        assertThat(entry.runId()).isEqualTo(tickQueuedRunId);
                        assertThat(entry.responsibleUserId()).isEqualTo(userId);
                        assertThat(entry.responsibilitySource()).isEqualTo("CLAIMED_WORKER");
                        assertThat(entry.modelRequestCount()).isGreaterThanOrEqualTo(1);
                        assertThat(entry.lastModelRequestId()).isEqualTo(modelExecution.requestId());
                    });
        }
    }

    @Test
    void 真实Mysql项目活动仓储使用MybatisPlus写读正式表() throws Exception {
        var jdbcUrl = env("MATRIXCODE_PERSISTENCE_JDBC_URL");
        var username = env("MATRIXCODE_PERSISTENCE_JDBC_USERNAME");
        var password = env("MATRIXCODE_PERSISTENCE_JDBC_PASSWORD");
        var projectId = "real-activity-" + UUID.randomUUID();
        var occurredAt = Instant.parse("2026-06-25T12:00:00Z");
        var request = new ModelRequestRecord(
                "real-request-" + UUID.randomUUID(),
                projectId,
                ModelRole.DEVELOPER,
                "deepseek",
                "deepseek-chat",
                "真实 MySQL 项目活动模型请求写读验证",
                "real-activity-user-" + UUID.randomUUID(),
                new UsageRecord(projectId + ":DEVELOPER", "deepseek-chat", 11, 22, 33, 0.25, 0.42, "CNY"),
                List.of("PROJECT_RULE", "VECTOR_CONTEXT"),
                occurredAt
        );
        var event = new ProjectEvent(
                "real-event-" + UUID.randomUUID(),
                projectId,
                "MODEL_REQUEST_COMPLETED",
                "真实 MySQL 项目活动事件写读验证",
                occurredAt.plusSeconds(3)
        );
        var stateDirectory = Files.createTempDirectory("matrixcode-real-activity-");

        try (var context = new SpringApplicationBuilder(MatrixCodeServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of(
                        "matrixcode.workbench-state.storage-path", stateDirectory.resolve("workbench-state.json").toString(),
                        "matrixcode.local-execution.storage-path", stateDirectory.resolve("local-execution.json").toString(),
                        "matrixcode.runtime-notifications.storage-path", stateDirectory.resolve("runtime-notifications.json").toString(),
                        "spring.main.banner-mode", "off"
                ))
                .run(
                        "--matrixcode.persistence.mode=jdbc",
                        "--matrixcode.persistence.jdbc.url=" + jdbcUrl,
                        "--matrixcode.persistence.jdbc.username=" + username,
                        "--matrixcode.persistence.jdbc.password=" + password,
                        "--matrixcode.persistence.jdbc.migrate-on-startup=true"
                )) {
            var repository = context.getBean(ProjectActivityRepository.class);
            assertThat(repository.getClass().getName()).contains("MybatisPlusProjectActivityRepository");

            repository.saveModelRequests(Map.of(projectId, List.of(request)));
            repository.saveProjectEvents(Map.of(projectId, List.of(event)));

            assertThat(repository.loadModelRequests().get(projectId)).containsExactly(request);
            assertThat(repository.loadProjectEvents().get(projectId)).containsExactly(event);
        }

        assertThat(realTableCount(jdbcUrl, username, password, "matrixcode_model_requests", projectId)).isEqualTo(1);
        assertThat(realTableCount(jdbcUrl, username, password, "matrixcode_project_events", projectId)).isEqualTo(1);
    }

    @Test
    void 真实Redis响应Ping() throws Exception {
        var host = envOrDefault("MATRIXCODE_REDIS_HOST", "127.0.0.1");
        var port = Integer.parseInt(envOrDefault("MATRIXCODE_REDIS_PORT", "6379"));

        try (var socket = connectedSocket(host, port)) {
            socket.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            var response = new String(socket.getInputStream().readNBytes(7), StandardCharsets.UTF_8);
            assertThat(response).startsWith("+PONG");
        }
    }

    @Test
    void 真实RocketMqNameServer端口可达() throws Exception {
        var endpoint = envOrDefault("MATRIXCODE_ROCKETMQ_NAME_SERVER", "127.0.0.1:9876");
        var separator = endpoint.lastIndexOf(':');
        assertThat(separator).isGreaterThan(0);

        try (var ignored = connectedSocket(endpoint.substring(0, separator), Integer.parseInt(endpoint.substring(separator + 1)))) {
            assertThat(ignored.isConnected()).isTrue();
        }
    }

    @Test
    void 真实RocketMq项目事件Topic可收发低敏消息() throws Exception {
        var marker = "real-runtime-" + UUID.randomUUID();
        var properties = new RocketMqProperties();
        properties.setNameServer(envOrDefault("MATRIXCODE_ROCKETMQ_NAME_SERVER", "127.0.0.1:9876"));
        properties.setTopicPrefix(envOrDefault("MATRIXCODE_ROCKETMQ_TOPIC_PREFIX", "matrixcode"));
        properties.getEventRelay().setProducerGroupSuffix("real-runtime-producer-" + marker);
        properties.getEventRelay().setConsumerGroupSuffix("real-runtime-consumer-" + marker);
        properties.getEventRelay().setSendTimeoutMs(5000);
        var codec = new ProjectEventRocketMqCodec(new ObjectMapper());
        var producer = new DefaultMQProducer(properties.eventRelayProducerGroup());
        var consumer = new DefaultMQPushConsumer(properties.eventRelayConsumerGroup());
        var received = new AtomicReference<ProjectEvent>();
        var receivedSignal = new CountDownLatch(1);
        var event = new ProjectEvent(
                "real-rocketmq-event-" + UUID.randomUUID(),
                "real-rocketmq-project-" + UUID.randomUUID(),
                "AGENT_REPORTED",
                "真实 RocketMQ 项目事件中继验证",
                Instant.parse("2026-06-29T10:00:00Z"),
                "DEVELOPER",
                marker
        );

        try {
            producer.setNamesrvAddr(properties.getNameServer());
            producer.setSendMsgTimeout(properties.getEventRelay().getSendTimeoutMs());
            producer.setVipChannelEnabled(false);
            consumer.setNamesrvAddr(properties.getNameServer());
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            consumer.setConsumeThreadMin(1);
            consumer.setConsumeThreadMax(2);
            consumer.subscribe(properties.eventRelayTopic(), properties.getEventRelay().getTag());
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                for (var message : messages) {
                    var decoded = codec.fromBody(message.getBody());
                    if (event.id().equals(decoded.id())) {
                        received.set(decoded);
                        receivedSignal.countDown();
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            producer.start();
            consumer.start();

            var sendResult = producer.send(codec.toMessage(event, properties), properties.getEventRelay().getSendTimeoutMs());

            assertThat(sendResult.getSendStatus()).isEqualTo(SendStatus.SEND_OK);
            assertThat(receivedSignal.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get()).isEqualTo(event);
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }

    @Test
    void 真实千问Embedding返回预期维度() {
        var properties = modelGatewayProperties();
        var client = new OpenAiCompatibleEmbeddingClient(
                new ObjectMapper(),
                new ModelProviderRegistry(properties),
                properties
        );

        assertThat(client.embed("MatrixCode 真实运行验证")).hasSize(properties.getVectorContext().getDimension());
    }

    @Test
    void 真实DeepSeekChat返回缓存用量字段() {
        var client = new OpenAiCompatibleModelClient(new ObjectMapper());

        var result = client.complete(
                new ModelProvider(
                        "deepseek",
                        "DeepSeek",
                        ModelProtocol.OPENAI_COMPATIBLE,
                        envOrDefault("MATRIXCODE_DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
                        "MATRIXCODE_DEEPSEEK_API_KEY",
                        true
                ),
                new RoleModelBinding(
                        "real-deepseek-cache",
                        ModelRole.DEVELOPER,
                        "deepseek",
                        envOrDefault("MATRIXCODE_DEEPSEEK_MODEL", "deepseek-chat"),
                        "CNY",
                        0.0,
                        0.0,
                        0.0,
                        32_000,
                        "tools-v1"
                ),
                new PromptContract(
                        ModelRole.DEVELOPER,
                        envOrDefault("MATRIXCODE_DEEPSEEK_MODEL", "deepseek-chat"),
                        "tools-v1",
                        "MatrixCode 真实 DeepSeek 缓存用量验证。请只输出短回答。",
                        "real-deepseek-cache-probe",
                        24
                ),
                "只回答 OK",
                "matrixcode_real_DEVELOPER_deepseek_deepseek-chat"
        );

        assertThat(result.answer()).isNotBlank();
        assertThat(result.usage()).hasValueSatisfying(usage -> {
            assertThat(usage.cacheHitTokens()).isGreaterThanOrEqualTo(0);
            assertThat(usage.cacheMissInputTokens()).isGreaterThan(0);
            assertThat(usage.outputTokens()).isGreaterThan(0);
        });
    }

    @Test
    void 真实Milvus可写入并召回向量上下文() {
        var properties = modelGatewayProperties();
        var store = new MilvusVectorContextStore(properties);
        var embedding = fixedEmbedding(properties.getVectorContext().getDimension());
        var marker = "real-runtime-" + UUID.randomUUID();
        var projectId = "real-runtime-" + UUID.randomUUID();
        try {
            store.upsert(new VectorContextEntry(
                    marker,
                    projectId,
                    ModelRole.DEVELOPER,
                    "RUNTIME_CHECK",
                    marker,
                    embedding
            ));

            assertThat(store.search(projectId, ModelRole.DEVELOPER, embedding, 1))
                    .anySatisfy(hit -> assertThat(hit.summary()).isEqualTo(marker));
        } finally {
            store.close();
        }
    }

    private static ModelGatewayProperties modelGatewayProperties() {
        var properties = new ModelGatewayProperties();
        properties.provider("qwen").setName("千问");
        properties.provider("qwen").setBaseUrl(envOrDefault(
                "MATRIXCODE_QWEN_BASE_URL",
                "https://dashscope.aliyuncs.com/compatible-mode/v1"
        ));
        properties.provider("qwen").setApiKeySource("MATRIXCODE_QWEN_API_KEY");
        properties.provider("qwen").setEnabled(true);
        properties.getEmbedding().setProviderId(envOrDefault("MATRIXCODE_EMBEDDING_PROVIDER_ID", "qwen"));
        properties.getEmbedding().setModel(envOrDefault("MATRIXCODE_EMBEDDING_MODEL", "text-embedding-v4"));
        properties.getVectorContext().setEnabled(true);
        properties.getVectorContext().setStore("milvus");
        properties.getVectorContext().setDimension(Integer.parseInt(envOrDefault(
                "MATRIXCODE_VECTOR_CONTEXT_DIMENSION",
                "1024"
        )));
        properties.getVectorContext().setTopK(Integer.parseInt(envOrDefault("MATRIXCODE_VECTOR_CONTEXT_TOP_K", "3")));
        properties.getMilvus().setHost(envOrDefault("MATRIXCODE_MILVUS_HOST", "127.0.0.1"));
        properties.getMilvus().setPort(Integer.parseInt(envOrDefault("MATRIXCODE_MILVUS_PORT", "19530")));
        properties.getMilvus().setDatabase(envOrDefault("MATRIXCODE_MILVUS_DATABASE", ""));
        properties.getMilvus().setCollection(envOrDefault(
                "MATRIXCODE_MILVUS_COLLECTION",
                "matrixcode_context_chunks_v2"
        ));
        properties.getMilvus().setSecure(Boolean.parseBoolean(envOrDefault("MATRIXCODE_MILVUS_SECURE", "false")));
        properties.getMilvus().setUsername(envOrDefault("MATRIXCODE_MILVUS_USERNAME", ""));
        properties.getMilvus().setPassword(envOrDefault("MATRIXCODE_MILVUS_PASSWORD", ""));
        properties.getMilvus().setToken(envOrDefault("MATRIXCODE_MILVUS_TOKEN", ""));
        return properties;
    }

    private static PersistenceModeProperties properties(String jdbcUrl, String username, String password) {
        var properties = new PersistenceModeProperties();
        properties.setMode("jdbc");
        properties.getJdbc().setUrl(jdbcUrl);
        properties.getJdbc().setUsername(username);
        properties.getJdbc().setPassword(password);
        return properties;
    }

    private static void insertRealAudit(
            String jdbcUrl,
            String username,
            String password,
            String auditId,
            String projectId,
            String userId,
            Instant occurredAt
    ) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = connection.prepareStatement("""
                     insert into matrixcode_audit_records
                         (id, project_id, actor_user_id, actor_role, action_key, target_type, target_id,
                          decision, summary, created_at, updated_at, task_id, tool_type, workspace_path,
                          occurred_at, sort_order)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, auditId);
            statement.setString(2, projectId);
            statement.setString(3, userId);
            statement.setString(4, "DEVELOPER");
            statement.setString(5, "REAL_RUNTIME_IDENTITY_CHECK");
            statement.setString(6, "REAL_RUNTIME");
            statement.setString(7, auditId);
            statement.setString(8, "ALLOW");
            statement.setString(9, "真实运行身份审计验证");
            statement.setTimestamp(10, Timestamp.from(occurredAt));
            statement.setTimestamp(11, Timestamp.from(occurredAt));
            statement.setString(12, auditId);
            statement.setString(13, "IDENTITY");
            statement.setString(14, "/tmp/matrixcode-real-runtime");
            statement.setTimestamp(15, Timestamp.from(occurredAt));
            statement.setInt(16, 0);
            statement.executeUpdate();
        }
    }

    /**
     * 统计真实 MySQL 中指定项目的领域表记录数。
     *
     * <p>表名来自测试内固定白名单调用点，不接受外部输入；项目 ID 使用参数绑定，
     * 避免真实联调时把测试辅助查询变成 SQL 注入入口。</p>
     */
    private static int realTableCount(
            String jdbcUrl,
            String username,
            String password,
            String tableName,
            String projectId
    ) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = connection.prepareStatement("select count(*) from " + tableName + " where project_id = ?")) {
            statement.setString(1, projectId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    /**
     * 统计真实 MySQL 中仍未使用单列 {@code id} 主键的 MatrixCode 正式业务表数量。
     *
     * <p>该断言用于防止后续 Flyway 迁移或历史表回退到业务字段主键；只读取
     * {@code information_schema}，不触碰业务数据和密钥。</p>
     */
    private static int nonIdPrimaryKeyTableCount(java.sql.Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("""
                select count(*)
                from (
                    select tc.table_name,
                           group_concat(lower(kcu.column_name) order by kcu.ordinal_position separator ',') pk_columns
                    from information_schema.table_constraints tc
                    join information_schema.key_column_usage kcu
                      on tc.constraint_schema = kcu.constraint_schema
                     and tc.constraint_name = kcu.constraint_name
                     and tc.table_name = kcu.table_name
                    where tc.constraint_schema = database()
                      and tc.constraint_type = 'PRIMARY KEY'
                      and lower(tc.table_name) like 'matrixcode\\_%'
                    group by tc.table_name
                    having pk_columns <> 'id'
                ) non_id_pk_tables
                """);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static Socket connectedSocket(String host, int port) throws Exception {
        var socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), (int) Duration.ofSeconds(3).toMillis());
        socket.setSoTimeout((int) Duration.ofSeconds(3).toMillis());
        return socket;
    }

    private static java.util.List<Float> fixedEmbedding(int dimension) {
        return java.util.stream.IntStream.range(0, dimension)
                .mapToObj(index -> index == 0 ? 1.0F : 0.0F)
                .toList();
    }

    private static String env(String name) {
        var value = System.getenv(name);
        assertThat(value)
                .as(name + " 必须通过 .env.local 或环境变量提供")
                .isNotBlank();
        return value;
    }

    private static String envOrDefault(String name, String defaultValue) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
