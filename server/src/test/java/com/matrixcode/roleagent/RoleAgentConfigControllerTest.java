package com.matrixcode.roleagent;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.identity.api.RequestActorResolver;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        MatrixCodeServerApplication.class,
        RoleAgentConfigControllerTest.IdentityRepositoryTestConfig.class
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RoleAgentConfigControllerTest {

    private static final Path LOCAL_EXECUTION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-local-execution-role-agent-" + System.nanoTime() + ".json");
    private static final Path RUNTIME_NOTIFICATION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-runtime-notifications-role-agent-" + System.nanoTime() + ".json");
    private static final Path WORKBENCH_STATE_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-workbench-state-role-agent-" + System.nanoTime() + ".json");

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
    void 返回四个默认角色智能体配置() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(get("/api/projects/demo/role-agent-configs")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].role").value("PRODUCT"))
                .andExpect(jsonPath("$[1].role").value("DEVELOPER"))
                .andExpect(jsonPath("$[1].displayName").value("开发智能体"))
                .andExpect(jsonPath("$[1].systemPrompt").value(containsString("代码修改")))
                .andExpect(jsonPath("$[1].cacheScopeStrategy").value("provider-model"))
                .andExpect(jsonPath("$[1].themeColor").value("#2563eb"));
    }

    @Test
    void 缺少登录身份时拒绝读取角色智能体配置() throws Exception {
        mockMvc.perform(get("/api/projects/demo/role-agent-configs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能读取角色智能体配置() throws Exception {
        mockMvc.perform(get("/api/projects/demo/role-agent-configs")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 可以更新开发角色智能体配置() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));

        mockMvc.perform(put("/api/projects/demo/role-agent-configs/developer")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDeveloperConfigJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.displayName").value("开发智能体 Pro"))
                .andExpect(jsonPath("$.model").value("matrixcode-local-developer-pro"))
                .andExpect(jsonPath("$.toolContractVersion").value("tools-v2"))
                .andExpect(jsonPath("$.cacheScopeStrategy").value("provider-role"))
                .andExpect(jsonPath("$.themeColor").value("#0f766e"))
                .andExpect(jsonPath("$.fontSize").value(15));
    }

    @Test
    void 缺少登录身份时拒绝更新角色智能体配置() throws Exception {
        mockMvc.perform(put("/api/projects/demo/role-agent-configs/developer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDeveloperConfigJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 普通成员不能更新角色智能体配置() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(put("/api/projects/demo/role-agent-configs/developer")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDeveloperConfigJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 非法角色返回400() throws Exception {
        repository.ensureMember(member("demo", "user-owner", "OWNER"));

        mockMvc.perform(put("/api/projects/demo/role-agent-configs/designer")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName":"设计智能体",
                                  "agentKind":"design",
                                  "providerId":"local-deterministic",
                                  "model":"matrixcode-local-designer",
                                  "toolContractVersion":"tools-v1",
                                  "systemPrompt":"你是设计智能体。",
                                  "userPromptTemplate":"请执行：{{instruction}}",
                                  "themeColor":"#f97316",
                                  "fontFamily":"Inter",
                                  "fontSize":14,
                                  "sortOrder":5,
                                  "enabled":true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("模型角色不合法")));
    }

    private String validDeveloperConfigJson() {
        return """
                {
                  "displayName":"开发智能体 Pro",
                  "agentKind":"coding",
                  "providerId":"local-deterministic",
                  "model":"matrixcode-local-developer-pro",
                  "toolContractVersion":"tools-v2",
                  "cachePolicyId":"deepseek-prefix-v2",
                  "volatileSuffixStrategy":"stable-prefix-dynamic-tail",
                  "cacheScopeStrategy":"provider-role",
                  "systemPrompt":"你是开发编码智能体，必须先读代码再修改。",
                  "userPromptTemplate":"请基于以下任务输出计划并执行：{{instruction}}",
                  "themeColor":"#0f766e",
                  "fontFamily":"Inter",
                  "fontSize":15,
                  "sortOrder":2,
                  "enabled":true
                }
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
