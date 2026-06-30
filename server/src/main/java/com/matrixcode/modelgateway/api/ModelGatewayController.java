package com.matrixcode.modelgateway.api;

import com.matrixcode.context.domain.ContextBlock;
import com.matrixcode.identity.api.ProjectRequestPermissionGuard;
import com.matrixcode.modelgateway.application.ModelGatewayService;
import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.application.RoleModelBindingService;
import com.matrixcode.modelgateway.application.VectorContextDocument;
import com.matrixcode.modelgateway.application.VectorContextService;
import com.matrixcode.modelgateway.domain.ModelCostTrendReport;
import com.matrixcode.modelgateway.domain.ModelGatewayConfig;
import com.matrixcode.modelgateway.domain.ModelProtocol;
import com.matrixcode.modelgateway.domain.ModelProvider;
import com.matrixcode.modelgateway.domain.ModelRequestCommand;
import com.matrixcode.modelgateway.domain.ModelRequestRuntimeOptions;
import com.matrixcode.modelgateway.domain.ModelRunRequestPage;
import com.matrixcode.modelgateway.domain.ModelResponse;
import com.matrixcode.modelgateway.domain.ModelRole;
import com.matrixcode.modelgateway.domain.RoleModelBinding;
import com.matrixcode.roleagent.application.RoleAgentConfigCommand;
import com.matrixcode.roleagent.application.RoleAgentConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 模型网关和向量上下文 API。
 *
 * <p>作用域：项目成员和项目管理角色；场景：配置模型供应商、绑定角色模型、发起角色模型请求、
 * 写入向量上下文和召回 RAG 上下文。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ModelGatewayController {

    private final ModelProviderRegistry providerRegistry;
    private final RoleModelBindingService bindingService;
    private final ModelGatewayService gatewayService;
    private final VectorContextService vectorContextService;
    private final RoleAgentConfigService roleAgentConfigService;
    private final ProjectRequestPermissionGuard requestPermissionGuard;

    public ModelGatewayController(
            ModelProviderRegistry providerRegistry,
            RoleModelBindingService bindingService,
            ModelGatewayService gatewayService,
            VectorContextService vectorContextService,
            RoleAgentConfigService roleAgentConfigService,
            ProjectRequestPermissionGuard requestPermissionGuard
    ) {
        this.providerRegistry = providerRegistry;
        this.bindingService = bindingService;
        this.gatewayService = gatewayService;
        this.vectorContextService = vectorContextService;
        this.roleAgentConfigService = roleAgentConfigService;
        this.requestPermissionGuard = requestPermissionGuard;
    }

    /**
     * 读取模型网关配置。
     *
     * <p>作用域：项目成员；场景：配置中心展示供应商、角色模型绑定和当前预算配置。</p>
     */
    @GetMapping("/model-gateway/config")
    public ModelGatewayConfig config(@PathVariable String projectId, HttpServletRequest request) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return gatewayService.config(projectId);
    }

    /**
     * 查询某次 Agent 运行关联的模型请求分页与成本趋势。
     *
     * <p>接口面向运行中心审计视图，只暴露低敏请求摘要和 usage 聚合，不返回 prompt、响应正文、
     * 工具输出、向量正文或供应商密钥。</p>
     */
    @GetMapping("/model-gateway/agent-runs/{agentRunId}/model-requests")
    public ModelRunRequestPage agentRunModelRequests(
            @PathVariable String projectId,
            @PathVariable String agentRunId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return gatewayService.agentRunModelRequests(projectId, agentRunId, page, size);
    }

    /**
     * 查询项目级长期模型成本趋势。
     *
     * <p>接口只返回低敏 usage 聚合，不返回 prompt、模型响应正文、工具输出、向量正文或密钥。</p>
     */
    @GetMapping("/model-gateway/cost-trends")
    public ModelCostTrendReport projectCostTrends(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "30") int days,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return gatewayService.projectCostTrends(projectId, days);
    }

    /**
     * 创建或更新模型供应商配置。
     *
     * <p>作用域：项目管理角色；场景：配置 OpenAI 兼容供应商的名称、协议、Base URL 和密钥来源变量名。</p>
     */
    @PostMapping("/model-gateway/providers")
    public ModelProvider upsertProvider(
            @PathVariable String projectId,
            @RequestBody ProviderCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertCanManageProject(request, projectId);
        return providerRegistry.upsert(new ModelProvider(
                command.id(),
                command.name(),
                command.protocol(),
                command.baseUrl(),
                command.apiKeySource(),
                command.enabled()
        ));
    }

    /**
     * 绑定角色默认模型。
     *
     * <p>作用域：项目管理角色；场景：为产品、开发、测试、运维指定供应商、模型、价格和工具契约版本。</p>
     */
    @PostMapping("/roles/{role}/model-binding")
    public RoleModelBinding bindRoleModel(
            @PathVariable String projectId,
            @PathVariable String role,
            @RequestBody RoleModelBindingCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertCanManageProject(request, projectId);
        var modelRole = ModelRole.fromPath(role);
        var binding = bindingService.bind(
                projectId,
                modelRole,
                command.providerId(),
                command.model(),
                command.currency(),
                command.cacheHitPerMillion(),
                command.cacheMissInputPerMillion(),
                command.outputPerMillion(),
                command.contextBudgetTokens(),
                command.toolContractVersion()
        );
        var current = roleAgentConfigService.require(projectId, modelRole);
        roleAgentConfigService.update(projectId, modelRole, new RoleAgentConfigCommand(
                current.displayName(),
                current.agentKind(),
                command.providerId(),
                command.model(),
                command.toolContractVersion(),
                current.systemPrompt(),
                current.userPromptTemplate(),
                current.themeColor(),
                current.fontFamily(),
                current.fontSize(),
                current.sortOrder(),
                current.enabled(),
                current.cachePolicyId(),
                current.volatileSuffixStrategy(),
                current.cacheScopeStrategy()
        ));
        return binding;
    }

    /**
     * 发起一次角色模型请求。
     *
     * <p>作用域：项目成员且操作者一致；场景：角色智能体生成草稿、交付、测试报告或运维建议。</p>
     */
    @PostMapping("/roles/{role}/model-requests")
    public ModelResponse createModelRequest(
            @PathVariable String projectId,
            @PathVariable String role,
            @RequestBody ModelRequestCommandBody command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMemberActor(request, projectId, command.actorUserId());
        return gatewayService.request(new ModelRequestCommand(
                projectId,
                ModelRole.fromPath(role),
                command.actorUserId(),
                command.agentRunId(),
                command.instruction(),
                command.contextBlocks(),
                command.runtimeOptions()
        ));
    }

    /**
     * 流式发起一次角色模型请求。
     *
     * <p>作用域：项目成员且操作者一致；场景：底部 Agent Composer 需要把供应商增量输出实时推送到
     * 工作台上方输出台。事件约定：`delta` 返回正文片段，`completed` 返回完整 `ModelResponse`，
     * `error` 返回可展示错误消息。</p>
     */
    @PostMapping(value = "/roles/{role}/model-requests/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamModelRequest(
            @PathVariable String projectId,
            @PathVariable String role,
            @RequestBody ModelRequestCommandBody command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMemberActor(request, projectId, command.actorUserId());
        var emitter = new SseEmitter(0L);
        var requestCommand = new ModelRequestCommand(
                projectId,
                ModelRole.fromPath(role),
                command.actorUserId(),
                command.agentRunId(),
                command.instruction(),
                command.contextBlocks(),
                command.runtimeOptions()
        );
        CompletableFuture.runAsync(() -> {
            try {
                var response = gatewayService.stream(requestCommand, delta -> sendEvent(
                        emitter,
                        "delta",
                        new ModelStreamDelta(delta)
                ));
                sendEvent(emitter, "completed", response);
                emitter.complete();
            } catch (UncheckedIOException exception) {
                sendError(emitter, exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage());
            } catch (Exception exception) {
                sendError(emitter, exception.getMessage());
            }
        });
        return emitter;
    }

    /**
     * 写入角色向量上下文文档。
     *
     * <p>作用域：项目成员；场景：把项目知识、交接内容或领域上下文写入 Milvus/内存向量库。</p>
     */
    @PostMapping("/roles/{role}/vector-context/documents")
    public VectorContextDocument upsertVectorContext(
            @PathVariable String projectId,
            @PathVariable String role,
            @RequestBody VectorContextCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        var document = new VectorContextDocument(
                projectId,
                ModelRole.fromPath(role),
                command.type(),
                command.summary()
        );
        vectorContextService.upsert(document);
        return document;
    }

    /**
     * 召回角色向量上下文。
     *
     * <p>作用域：项目成员；场景：模型请求前按用户指令检索可注入 Prompt 的相关上下文块。</p>
     */
    @PostMapping("/roles/{role}/vector-context/recall")
    public List<ContextBlock> recallVectorContext(
            @PathVariable String projectId,
            @PathVariable String role,
            @RequestBody VectorRecallCommand command,
            HttpServletRequest request
    ) {
        requestPermissionGuard.assertProjectMember(request, projectId);
        return vectorContextService.recall(projectId, ModelRole.fromPath(role), command.instruction());
    }

    /**
     * 保存模型供应商配置的请求体。
     *
     * <p>作用域：模型网关管理 API；场景：配置千问、DeepSeek、Kimi、豆包等供应商的协议、地址和密钥来源。</p>
     */
    public record ProviderCommand(
            String id,
            String name,
            ModelProtocol protocol,
            String baseUrl,
            String apiKeySource,
            boolean enabled
    ) {
    }

    /**
     * 保存角色默认模型绑定的请求体。
     *
     * <p>作用域：模型网关管理 API；场景：为产品、开发、测试、部署等角色配置默认模型和计费参数。</p>
     */
    public record RoleModelBindingCommand(
            String providerId,
            String model,
            String currency,
            double cacheHitPerMillion,
            double cacheMissInputPerMillion,
            double outputPerMillion,
            int contextBudgetTokens,
            String toolContractVersion
    ) {
    }

    /**
     * 发起角色智能体模型请求的请求体。
     *
     * <p>作用域：模型网关运行 API；场景：携带操作者、Agent 运行编号、用户指令和上下文块调用绑定模型。</p>
     */
    public record ModelRequestCommandBody(
            String actorUserId,
            String agentRunId,
            String instruction,
            List<ContextBlock> contextBlocks,
            String providerId,
            String model,
            String approvalMode,
            String reasoningEffort,
            Boolean planMode,
            Boolean goalMode,
            Boolean tokenEconomy
    ) {
        /**
         * 转换前端 Agent Composer 的可选运行参数。
         *
         * <p>旧调用不传这些字段时会回落到默认值；新调用会把本轮模型选择、权限模式和协作方式写入
         * 后端模型请求上下文，并传递给支持的供应商客户端。</p>
         */
        ModelRequestRuntimeOptions runtimeOptions() {
            return new ModelRequestRuntimeOptions(
                    providerId,
                    model,
                    approvalMode,
                    reasoningEffort,
                    Boolean.TRUE.equals(planMode),
                    Boolean.TRUE.equals(goalMode),
                    Boolean.TRUE.equals(tokenEconomy)
            );
        }
    }

    /**
     * SSE 增量正文事件。
     *
     * <p>作用域：模型流式 API；场景：前端把 `delta` 追加到当前输出台。</p>
     */
    public record ModelStreamDelta(String delta) {
    }

    /**
     * SSE 错误事件。
     *
     * <p>作用域：模型流式 API；场景：供应商或服务端异常时让前端展示稳定错误消息。</p>
     */
    public record ModelStreamError(String message) {
    }

    /**
     * 向 SSE 连接写入一个具名事件。
     *
     * <p>作用域：模型流式 API 内部；场景：把 delta、completed 等事件统一写出，写入失败时转为
     * `UncheckedIOException` 交给外层结束连接。</p>
     */
    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 向 SSE 连接写入错误事件并关闭连接。
     *
     * <p>作用域：模型流式 API 内部；场景：供应商异常、服务异常或客户端断开时，保证前端收到稳定错误
     * 结构，且服务端不会继续持有 emitter。</p>
     */
    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(new ModelStreamError(
                    message == null || message.isBlank() ? "模型流式请求失败" : message
            )));
        } catch (IOException ignored) {
            // 客户端已断开时无需继续写入。
        } finally {
            emitter.complete();
        }
    }

    /**
     * 写入向量上下文的请求体。
     *
     * <p>作用域：模型网关 RAG API；场景：把需求、交付、测试或部署摘要写入 Milvus 召回库。</p>
     */
    public record VectorContextCommand(String type, String summary) {
    }

    /**
     * 召回向量上下文的请求体。
     *
     * <p>作用域：模型网关 RAG API；场景：根据当前角色指令检索相关项目知识片段。</p>
     */
    public record VectorRecallCommand(String instruction) {
    }
}
