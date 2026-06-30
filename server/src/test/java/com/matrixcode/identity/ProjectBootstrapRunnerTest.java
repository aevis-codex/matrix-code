package com.matrixcode.identity;

import com.matrixcode.identity.application.ProjectBootstrapProperties;
import com.matrixcode.identity.application.ProjectBootstrapRunner;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.application.ProjectIdentityService;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectBootstrapRunnerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-29T06:10:00Z"), ZoneOffset.UTC);

    @Test
    void 配置关闭时不会创建首个Owner() {
        var repository = new RecordingProjectIdentityRepository();
        var properties = new ProjectBootstrapProperties();
        properties.setEnabled(false);
        properties.setProjectId("demo");
        properties.setOwnerUserId("user-owner");

        new ProjectBootstrapRunner(properties, new ProjectIdentityService(repository, FIXED_CLOCK)).run(null);

        assertThat(repository.members("demo")).isEmpty();
    }

    @Test
    void 配置开启时为空项目创建首个Owner() {
        var repository = new RecordingProjectIdentityRepository();
        var properties = new ProjectBootstrapProperties();
        properties.setEnabled(true);
        properties.setProjectId("demo");
        properties.setProjectName("MatrixCode Demo");
        properties.setOwnerUserId("user-owner");
        properties.setOwnerDisplayName("项目负责人");
        properties.setCurrentStage("真实库初始化");

        new ProjectBootstrapRunner(properties, new ProjectIdentityService(repository, FIXED_CLOCK)).run(null);

        assertThat(repository.members("demo"))
                .extracting(ProjectMember::userId, ProjectMember::roleKey)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("user-owner", "OWNER"));
    }

    private static final class RecordingProjectIdentityRepository implements ProjectIdentityRepository {
        private final List<ProjectMember> members = new ArrayList<>();

        @Override
        public void ensureUser(MatrixUser user) {
        }

        @Override
        public void ensureProject(String projectId, String name, String ownerUserId, String currentStage) {
        }

        @Override
        public void ensureMember(ProjectMember member) {
            members.add(member);
        }

        @Override
        public List<ProjectMember> members(String projectId) {
            return members.stream()
                    .filter(member -> member.projectId().equals(projectId))
                    .toList();
        }

        @Override
        public List<String> projectsForUser(String userId) {
            return List.of();
        }

        @Override
        public List<UserAuditRecord> auditRecords(String projectId, String userId) {
            return List.of();
        }
    }
}
