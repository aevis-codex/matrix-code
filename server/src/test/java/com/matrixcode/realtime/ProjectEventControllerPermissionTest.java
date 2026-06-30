package com.matrixcode.realtime;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.identity.api.RequestActorResolver;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.domain.ProjectEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        MatrixCodeServerApplication.class,
        ProjectEventControllerPermissionTest.IdentityRepositoryTestConfig.class
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProjectEventControllerPermissionTest {

    private static final Path WORKBENCH_STATE_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-project-events-" + System.nanoTime() + ".json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectEventBus eventBus;

    @Autowired
    private FakeProjectIdentityRepository repository;

    @DynamicPropertySource
    static void persistentStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("matrixcode.workbench-state.storage-path", WORKBENCH_STATE_STORAGE::toString);
    }

    @AfterEach
    void cleanPersistentStorage() throws Exception {
        Files.deleteIfExists(WORKBENCH_STATE_STORAGE);
    }

    @Test
    void 事件列表缺少请求身份时返回401() throws Exception {
        mockMvc.perform(get("/api/projects/demo/events"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能读取事件列表() throws Exception {
        mockMvc.perform(get("/api/projects/demo/events")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目成员可以读取事件列表() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        eventBus.publish(new ProjectEvent("demo", "WORKFLOW_CHANGED", "需求已冻结"));

        mockMvc.perform(get("/api/projects/demo/events")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").value("demo"))
                .andExpect(jsonPath("$[0].type").value("WORKFLOW_CHANGED"))
                .andExpect(jsonPath("$[0].message").value("需求已冻结"));
    }

    @Test
    void 事件流缺少请求身份时返回401() throws Exception {
        mockMvc.perform(get("/api/projects/demo/events/stream"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能订阅事件流() throws Exception {
        mockMvc.perform(get("/api/projects/demo/events/stream")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目成员可以订阅事件流() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(get("/api/projects/demo/events/stream")
                        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    void 非项目成员不能通过事件流URL身份参数订阅() throws Exception {
        mockMvc.perform(get("/api/projects/demo/events/stream")
                        .param("actorUserId", "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目成员可以通过事件流URL身份参数订阅() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(get("/api/projects/demo/events/stream")
                        .param("actorUserId", "user-dev"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    void 事件流URL身份令牌无效时返回401() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(get("/api/projects/demo/events/stream")
                        .param("actorUserId", "user-dev")
                        .param("actorToken", "invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    private ProjectMember member(String projectId, String userId, String roleKey) {
        var now = Instant.parse("2026-06-29T10:00:00Z");
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
