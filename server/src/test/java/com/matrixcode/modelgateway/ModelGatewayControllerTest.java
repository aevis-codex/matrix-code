package com.matrixcode.modelgateway;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.matrixcode.identity.api.RequestActorResolver.CURRENT_USER_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        MatrixCodeServerApplication.class,
        ModelGatewayControllerTest.IdentityRepositoryTestConfig.class
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ModelGatewayControllerTest {

    private static final Path LOCAL_EXECUTION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-local-execution-model-gateway-" + System.nanoTime() + ".json");
    private static final Path RUNTIME_NOTIFICATION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-runtime-notifications-model-gateway-" + System.nanoTime() + ".json");
    private static final Path WORKBENCH_STATE_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-workbench-state-model-gateway-" + System.nanoTime() + ".json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeProjectIdentityRepository repository;

    @DynamicPropertySource
    static void persistentStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("matrixcode.local-execution.storage-path", LOCAL_EXECUTION_STORAGE::toString);
        registry.add("matrixcode.runtime-notifications.storage-path", RUNTIME_NOTIFICATION_STORAGE::toString);
        registry.add("matrixcode.workbench-state.storage-path", WORKBENCH_STATE_STORAGE::toString);
    }

    @AfterEach
    void cleanPersistentStorage() throws Exception {
        Files.deleteIfExists(LOCAL_EXECUTION_STORAGE);
        Files.deleteIfExists(RUNTIME_NOTIFICATION_STORAGE);
        Files.deleteIfExists(WORKBENCH_STATE_STORAGE);
    }

    @Test
    void 返回默认模型网关配置() throws Exception {
        repository.ensureMember(member("demo", "user-product", "PRODUCT"));

        mockMvc.perform(get("/api/projects/demo/model-gateway/config")
                        .header(CURRENT_USER_HEADER, "user-product"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[*].id").value(
                        org.hamcrest.Matchers.hasItems("local-deterministic", "qwen", "deepseek")
                ))
                .andExpect(jsonPath("$.bindings.length()").value(4))
                .andExpect(jsonPath("$.metrics.requestCount").value(0));
    }

    @Test
    void 读取模型网关配置缺少请求身份时返回401() throws Exception {
        mockMvc.perform(get("/api/projects/demo/model-gateway/config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能读取模型网关配置() throws Exception {
        mockMvc.perform(get("/api/projects/demo/model-gateway/config")
                        .header(CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 产品角色可以创建模型请求() throws Exception {
        repository.ensureMember(member("demo", "user-product", "PRODUCT"));

        mockMvc.perform(post("/api/projects/demo/roles/product/model-requests")
                        .header(CURRENT_USER_HEADER, "user-product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorUserId":"user-product","instruction":"支付失败后允许用户重新发起支付。","contextBlocks":[{"type":"PROJECT_RULE","summary":"保持中文输出","allowedByGate":true}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("产品需求草稿")))
                .andExpect(jsonPath("$.contextManifest.blocks[0].type").value("PROJECT_RULE"))
                .andExpect(jsonPath("$.usage.roleSessionId").value("demo:PRODUCT"));

        mockMvc.perform(get("/api/projects/demo/model-gateway/config")
                        .header(CURRENT_USER_HEADER, "user-product"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentRequests[0].actorUserId").value("user-product"));
    }

    @Test
    void AgentComposer字段会进入后端模型请求上下文() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(post("/api/projects/demo/roles/developer/model-requests")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorUserId":"user-dev",
                                  "instruction":"重构登录工作台布局。",
                                  "providerId":"local-deterministic",
                                  "model":"matrixcode-local-developer",
                                  "approvalMode":"auto",
                                  "reasoningEffort":"max",
                                  "planMode":true,
                                  "goalMode":false,
                                  "tokenEconomy":true,
                                  "contextBlocks":[{"type":"WORKBENCH_STAGE","summary":"当前阶段：开发中","allowedByGate":true}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binding.providerId").value("local-deterministic"))
                .andExpect(jsonPath("$.binding.model").value("matrixcode-local-developer"))
                .andExpect(jsonPath("$.contextManifest.blocks[*].type").value(
                        org.hamcrest.Matchers.hasItems("WORKBENCH_STAGE", "COMPOSER_RUNTIME")
                ))
                .andExpect(jsonPath("$.contextManifest.blocks[?(@.type=='COMPOSER_RUNTIME')].summary").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("工具权限：自动"),
                                org.hamcrest.Matchers.containsString("推理力度：max"),
                                org.hamcrest.Matchers.containsString("协作方式：计划、省 token")
                        ))
                ));
    }

    @Test
    void 创建模型请求缺少请求身份时返回401() throws Exception {
        mockMvc.perform(post("/api/projects/demo/roles/product/model-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorUserId":"user-product","instruction":"支付失败后允许用户重新发起支付。","contextBlocks":[]}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 创建模型请求身份与操作者不一致时返回403() throws Exception {
        repository.ensureMember(member("demo", "user-product", "PRODUCT"));
        repository.ensureMember(member("demo", "user-reviewer", "TESTER"));

        mockMvc.perform(post("/api/projects/demo/roles/product/model-requests")
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorUserId":"user-product","instruction":"支付失败后允许用户重新发起支付。","contextBlocks":[]}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 非项目成员不能创建模型请求() throws Exception {
        mockMvc.perform(post("/api/projects/demo/roles/product/model-requests")
                        .header(CURRENT_USER_HEADER, "user-outsider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorUserId":"user-outsider","instruction":"支付失败后允许用户重新发起支付。","contextBlocks":[]}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 可以按Agent运行分页查看模型请求和成本趋势() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(post("/api/projects/demo/roles/developer/model-requests")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorUserId":"user-dev","agentRunId":"run-cost","instruction":"生成第一版实现计划。","contextBlocks":[{"type":"PROJECT_RULE","summary":"保持中文输出","allowedByGate":true}]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/demo/roles/developer/model-requests")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorUserId":"user-dev","agentRunId":"run-other","instruction":"其他运行请求。","contextBlocks":[]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/demo/roles/developer/model-requests")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorUserId":"user-dev","agentRunId":"run-cost","instruction":"生成第二版实现计划。","contextBlocks":[{"type":"FROZEN_PRD","summary":"需求已冻结","allowedByGate":true}]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/demo/model-gateway/agent-runs/run-cost/model-requests")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.agentRunId").value("run-cost"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].agentRunId").value("run-cost"))
                .andExpect(jsonPath("$.metrics.requestCount").value(2))
                .andExpect(jsonPath("$.trend.length()").value(2))
                .andExpect(jsonPath("$.trend[0].requestId").exists())
                .andExpect(jsonPath("$.trend[0].estimatedCost").exists());
    }

    @Test
    void 可以查看项目级长期模型成本趋势() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        repository.ensureMember(member("demo", "user-product", "PRODUCT"));

        mockMvc.perform(post("/api/projects/demo/roles/developer/model-requests")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorUserId":"user-dev","agentRunId":"run-cost","instruction":"生成第一版实现计划。","contextBlocks":[{"type":"PROJECT_RULE","summary":"保持中文输出","allowedByGate":true}]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/demo/roles/developer/model-requests")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorUserId":"user-dev","agentRunId":"run-cost","instruction":"生成第二版实现计划。","contextBlocks":[{"type":"FROZEN_PRD","summary":"需求已冻结","allowedByGate":true}]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/demo/roles/product/model-requests")
                        .header(CURRENT_USER_HEADER, "user-product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorUserId":"user-product","instruction":"梳理支付失败后的重试策略。","contextBlocks":[]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/demo/model-gateway/cost-trends")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.timeZone").value("UTC"))
                .andExpect(jsonPath("$.metrics.requestCount").value(3))
                .andExpect(jsonPath("$.dailyTrend.length()").value(1))
                .andExpect(jsonPath("$.dailyTrend[0].metrics.requestCount").value(3))
                .andExpect(jsonPath("$.roleBreakdown[*].key").value(
                        org.hamcrest.Matchers.hasItems("DEVELOPER", "PRODUCT")
                ))
                .andExpect(jsonPath("$.providerBreakdown[*].key").value(
                        org.hamcrest.Matchers.hasItem("local-deterministic")
                ))
                .andExpect(jsonPath("$.modelBreakdown.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void 按Agent运行读取模型请求缺少请求身份时返回401() throws Exception {
        mockMvc.perform(get("/api/projects/demo/model-gateway/agent-runs/run-cost/model-requests"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能按Agent运行读取模型请求() throws Exception {
        mockMvc.perform(get("/api/projects/demo/model-gateway/agent-runs/run-cost/model-requests")
                        .header(CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目级模型成本趋势缺少请求身份时返回401() throws Exception {
        mockMvc.perform(get("/api/projects/demo/model-gateway/cost-trends"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能查看项目级模型成本趋势() throws Exception {
        mockMvc.perform(get("/api/projects/demo/model-gateway/cost-trends")
                        .header(CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 可以通过接口绑定测试角色模型() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));
        mockMvc.perform(post("/api/projects/demo/roles/tester/model-binding")
                        .header(CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerId":"local-deterministic","model":"matrixcode-local-tester-v2","currency":"CNY","cacheHitPerMillion":0.0,"cacheMissInputPerMillion":0.0,"outputPerMillion":0.0,"contextBudgetTokens":48000,"toolContractVersion":"tools-v2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TESTER"))
                .andExpect(jsonPath("$.model").value("matrixcode-local-tester-v2"))
                .andExpect(jsonPath("$.contextBudgetTokens").value(48000));

        mockMvc.perform(get("/api/projects/demo/role-agent-configs")
                        .header(CURRENT_USER_HEADER, "user-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.role=='TESTER')].providerId").value(
                        org.hamcrest.Matchers.contains("local-deterministic")
                ))
                .andExpect(jsonPath("$[?(@.role=='TESTER')].model").value(
                        org.hamcrest.Matchers.contains("matrixcode-local-tester-v2")
                ))
                .andExpect(jsonPath("$[?(@.role=='TESTER')].toolContractVersion").value(
                        org.hamcrest.Matchers.contains("tools-v2")
                ));

        mockMvc.perform(post("/api/projects/demo/roles/tester/model-requests")
                        .header(CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorUserId":"user-owner","instruction":"验证模型绑定会驱动实际请求。","contextBlocks":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binding.providerId").value("local-deterministic"))
                .andExpect(jsonPath("$.binding.model").value("matrixcode-local-tester-v2"))
                .andExpect(jsonPath("$.binding.toolContractVersion").value("tools-v2"));
    }

    @Test
    void 绑定角色模型缺少请求身份时返回401() throws Exception {
        mockMvc.perform(post("/api/projects/demo/roles/tester/model-binding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleModelBindingJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 普通项目成员不能绑定角色模型() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(post("/api/projects/demo/roles/tester/model-binding")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleModelBindingJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目管理角色可以绑定角色模型() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));

        mockMvc.perform(post("/api/projects/demo/roles/tester/model-binding")
                        .header(CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleModelBindingJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TESTER"))
                .andExpect(jsonPath("$.model").value("matrixcode-local-tester-v2"));
    }

    @Test
    void 配置模型供应商缺少请求身份时返回401() throws Exception {
        mockMvc.perform(post("/api/projects/demo/model-gateway/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modelProviderJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 普通项目成员不能配置模型供应商() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(post("/api/projects/demo/model-gateway/providers")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modelProviderJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目管理角色可以配置模型供应商() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));

        mockMvc.perform(post("/api/projects/demo/model-gateway/providers")
                        .header(CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modelProviderJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("custom-openai"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void 向量上下文接口默认关闭时可写入且召回为空() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(post("/api/projects/demo/roles/developer/vector-context/documents")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"HANDOFF_DOCUMENT","summary":"交接文档必须包含部署步骤和回滚说明"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.type").value("HANDOFF_DOCUMENT"));

        mockMvc.perform(post("/api/projects/demo/roles/developer/vector-context/recall")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instruction":"生成交接文档"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 向量上下文写入缺少请求身份时返回401() throws Exception {
        mockMvc.perform(post("/api/projects/demo/roles/developer/vector-context/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"HANDOFF_DOCUMENT","summary":"交接文档必须包含部署步骤和回滚说明"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能召回向量上下文() throws Exception {
        mockMvc.perform(post("/api/projects/demo/roles/developer/vector-context/recall")
                        .header(CURRENT_USER_HEADER, "user-outsider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instruction":"生成交接文档"}
                                """))
                .andExpect(status().isForbidden());
    }

    private String roleModelBindingJson() {
        return """
                {"providerId":"local-deterministic","model":"matrixcode-local-tester-v2","currency":"CNY","cacheHitPerMillion":0.0,"cacheMissInputPerMillion":0.0,"outputPerMillion":0.0,"contextBudgetTokens":48000,"toolContractVersion":"tools-v2"}
                """;
    }

    private String modelProviderJson() {
        return """
                {"id":"custom-openai","name":"自定义兼容供应商","protocol":"OPENAI_COMPATIBLE","baseUrl":"https://model.example.com/v1","apiKeySource":"MATRIXCODE_CUSTOM_API_KEY","enabled":true}
                """;
    }

    private ProjectMember member(String projectId, String userId, String roleKey) {
        var now = Instant.parse("2026-06-25T13:50:00Z");
        return new ProjectMember(projectId + ":" + userId + ":" + roleKey, projectId, userId, roleKey, "ACTIVE", now, now, now);
    }

    @TestConfiguration
    static class IdentityRepositoryTestConfig {
        @Bean
        FakeProjectIdentityRepository fakeProjectIdentityRepository() {
            return new FakeProjectIdentityRepository();
        }
    }

    static class FakeProjectIdentityRepository implements ProjectIdentityRepository {

        private final Map<String, MatrixUser> users = new LinkedHashMap<>();
        private final Map<String, ProjectMember> members = new LinkedHashMap<>();
        private final List<UserAuditRecord> auditRecords = new ArrayList<>();

        @Override
        public void ensureUser(MatrixUser user) {
            users.put(user.id(), user);
        }

        @Override
        public void ensureProject(String projectId, String name, String ownerUserId, String currentStage) {
        }

        @Override
        public void ensureMember(ProjectMember member) {
            members.put(member.projectId() + ":" + member.userId() + ":" + member.roleKey(), member);
        }

        @Override
        public List<ProjectMember> members(String projectId) {
            return members.values().stream()
                    .filter(member -> member.projectId().equals(projectId))
                    .toList();
        }

        @Override
        public List<String> projectsForUser(String userId) {
            return members.values().stream()
                    .filter(member -> member.userId().equals(userId))
                    .map(ProjectMember::projectId)
                    .distinct()
                    .sorted()
                    .toList();
        }

        @Override
        public List<UserAuditRecord> auditRecords(String projectId, String userId) {
            return auditRecords.stream()
                    .filter(record -> record.projectId().equals(projectId))
                    .filter(record -> record.actorUserId().equals(userId))
                    .toList();
        }
    }
}
