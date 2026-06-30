package com.matrixcode.api;

import com.jayway.jsonpath.JsonPath;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        MatrixCodeServerApplication.class,
        ProjectOverviewControllerTest.IdentityRepositoryTestConfig.class
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProjectOverviewControllerTest {

    private static final Path LOCAL_EXECUTION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-local-execution-overview-" + System.nanoTime() + ".json");
    private static final Path RUNTIME_NOTIFICATION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-runtime-notifications-overview-" + System.nanoTime() + ".json");
    private static final Path WORKBENCH_STATE_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-workbench-state-overview-" + System.nanoTime() + ".json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectIdentityRepository repository;

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
    void 项目概览缺少请求身份时返回401() throws Exception {
        mockMvc.perform(get("/api/projects/demo/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能读取项目概览() throws Exception {
        mockMvc.perform(get("/api/projects/demo/overview")
                        .header(CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目概览从工作台读取当前阶段并保持旧字段() throws Exception {
        repository.ensureMember(member("demo", "user-product", "PRODUCT"));

        mockMvc.perform(post("/api/projects/demo/roles/product/drafts")
                        .header(CURRENT_USER_HEADER, "user-product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"支付失败后允许用户重新发起支付。\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/demo/overview")
                        .header(CURRENT_USER_HEADER, "user-product"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.projectName").value("MatrixCode 项目"))
                .andExpect(jsonPath("$.roles[0]").value("产品"))
                .andExpect(jsonPath("$.roles[1]").value("开发"))
                .andExpect(jsonPath("$.roles[2]").value("测试"))
                .andExpect(jsonPath("$.roles[3]").value("运维"))
                .andExpect(jsonPath("$.stages[0]").value("需求录入"))
                .andExpect(jsonPath("$.stages[?(@ == '上线准备')]").isNotEmpty())
                .andExpect(jsonPath("$.cacheHitRate").value(0.0))
                .andExpect(jsonPath("$.sessionTokens").value(greaterThan(0)))
                .andExpect(jsonPath("$.currentStage").value("需求草稿"));
    }

    @Test
    void 项目概览在存在未关闭严重Bug时展示缺陷处理中阶段() throws Exception {
        repository.ensureMember(member("demo", "user-product", "PRODUCT"));
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        repository.ensureMember(member("demo", "user-tester", "TESTER"));

        var draftsResponse = mockMvc.perform(post("/api/projects/demo/roles/product/drafts")
                        .header(CURRENT_USER_HEADER, "user-product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"支付失败后允许用户重新发起支付。\"}"))
                .andExpect(status().isOk())
                .andReturn();
        var documentId = JsonPath.read(draftsResponse.getResponse().getContentAsString(), "$[0].id").toString();
        mockMvc.perform(post("/api/projects/demo/documents/" + documentId + "/freeze")
                        .header(CURRENT_USER_HEADER, "user-product"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/demo/roles/developer/deliveries")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspacePath":"/repo/payment","implementationNote":"完成失败态处理","selfTestResult":"测试通过","apiDoc":"GET /payments/{id}","databaseScript":"alter table payment add fail_reason varchar(255);","deploymentDoc":"docker compose up -d"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/demo/bugs")
                        .header(CURRENT_USER_HEADER, "user-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"失败原因为空","severity":"HIGH","steps":"支付失败","expected":"显示失败原因","actual":"为空白","createdByRole":"测试","currentOwnerRole":"开发"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/demo/overview")
                        .header(CURRENT_USER_HEADER, "user-product"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("缺陷处理中"))
                .andExpect(jsonPath("$.stages[?(@ == '缺陷处理中')]").isNotEmpty());
    }

    @Test
    void 项目概览复制列表避免外部改写响应对象() {
        var roles = new ArrayList<String>();
        roles.add("产品");
        var stages = new ArrayList<String>();
        stages.add("需求");

        var overview = new ProjectOverview("demo", "支付系统重构", roles, stages, 0.86, 221_000, "测试执行");
        roles.add("开发");
        stages.add("测试");

        assertThat(overview.roles()).containsExactly("产品");
        assertThat(overview.stages()).containsExactly("需求");
        assertThatThrownBy(() -> overview.roles().add("测试"))
                .isInstanceOf(UnsupportedOperationException.class);
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
            return List.of();
        }
    }
}
