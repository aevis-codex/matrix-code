package com.matrixcode.runtimecheck;

import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.matrixcode.identity.api.RequestActorResolver.CURRENT_USER_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        MatrixCodeServerApplication.class,
        RuntimeDiagnosticsControllerTest.IdentityRepositoryTestConfig.class
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RuntimeDiagnosticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectIdentityRepository repository;

    @Test
    void 项目级运行诊断缺少请求身份时返回401() throws Exception {
        mockMvc.perform(get("/api/projects/demo/runtime-diagnostics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能读取运行诊断() throws Exception {
        mockMvc.perform(get("/api/projects/demo/runtime-diagnostics")
                        .header(CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目级运行诊断接口返回报告() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));

        mockMvc.perform(get("/api/projects/demo/runtime-diagnostics")
                        .header(CURRENT_USER_HEADER, "user-ops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.nextActions").isArray());
    }

    private ProjectMember member(String projectId, String userId, String roleKey) {
        var now = Instant.parse("2026-06-29T11:30:00Z");
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
