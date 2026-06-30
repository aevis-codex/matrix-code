package com.matrixcode.localexecution;

import com.jayway.jsonpath.JsonPath;
import com.matrixcode.MatrixCodeServerApplication;
import com.matrixcode.identity.application.ProjectIdentityRepository;
import com.matrixcode.identity.domain.MatrixUser;
import com.matrixcode.identity.domain.ProjectMember;
import com.matrixcode.identity.domain.UserAuditRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        MatrixCodeServerApplication.class,
        LocalExecutionControllerTest.IdentityRepositoryTestConfig.class
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LocalExecutionControllerTest {

    private static final Path LOCAL_EXECUTION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-local-execution-controller-" + System.nanoTime() + ".json");
    private static final Path RUNTIME_NOTIFICATION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-runtime-notifications-controller-" + System.nanoTime() + ".json");
    private static final Path WORKBENCH_STATE_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-workbench-state-controller-" + System.nanoTime() + ".json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeProjectIdentityRepository repository;

    @TempDir
    Path workspace;

    @DynamicPropertySource
    static void localExecutionProperties(DynamicPropertyRegistry registry) {
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
    void 授权本地工作区缺少请求身份时返回401() throws Exception {
        mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"当前项目","rootPath":"%s"}
                                """.formatted(escapePath(workspace))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能授权本地工作区() throws Exception {
        mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .header(CURRENT_USER_HEADER, "user-stranger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"当前项目","rootPath":"%s"}
                                """.formatted(escapePath(workspace))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目成员可以授权本地工作区() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));

        mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"当前项目","rootPath":"%s"}
                                """.formatted(escapePath(workspace))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("当前项目"));
    }

    @Test
    void 读取本地执行摘要缺少请求身份时返回401() throws Exception {
        authorizeWorkspace();

        mockMvc.perform(get("/api/projects/demo/local-execution/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能读取本地执行摘要() throws Exception {
        authorizeWorkspace();

        mockMvc.perform(get("/api/projects/demo/local-execution/summary")
                        .header(CURRENT_USER_HEADER, "user-stranger"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 查询本地任务日志缺少请求身份时返回401() throws Exception {
        var workspaceId = authorizeWorkspace();
        var commandResponse = mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-dev","command":"git status"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andReturn();
        var taskId = JsonPath.read(commandResponse.getResponse().getContentAsString(), "$.taskId").toString();

        mockMvc.perform(get("/api/projects/demo/local-execution/commands/%s/logs".formatted(taskId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能查询本地任务日志() throws Exception {
        var workspaceId = authorizeWorkspace();
        var commandResponse = mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-dev","command":"git status"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andReturn();
        var taskId = JsonPath.read(commandResponse.getResponse().getContentAsString(), "$.taskId").toString();

        mockMvc.perform(get("/api/projects/demo/local-execution/commands/%s/logs".formatted(taskId))
                        .header(CURRENT_USER_HEADER, "user-stranger"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 提交本地命令缺少请求身份时返回401() throws Exception {
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-dev","command":"git status"}
                                """.formatted(workspaceId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 提交本地命令请求身份和操作者不一致时返回403() throws Exception {
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, "user-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-dev","command":"git status"}
                                """.formatted(workspaceId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 审批本地命令请求身份和操作者不一致时返回403() throws Exception {
        var workspaceId = authorizeWorkspace();
        var commandResponse = mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-dev","command":"git status"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andReturn();
        var taskId = JsonPath.read(commandResponse.getResponse().getContentAsString(), "$.taskId").toString();

        mockMvc.perform(post("/api/projects/demo/local-execution/commands/%s/approval".formatted(taskId))
                        .header(CURRENT_USER_HEADER, "user-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorId":"user-reviewer","decision":"DENY","note":"本轮不执行"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 取消本地命令请求身份和操作者不一致时返回403() throws Exception {
        var workspaceId = authorizeWorkspace();
        var commandResponse = mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-dev","command":"sleep 5"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andReturn();
        var taskId = JsonPath.read(commandResponse.getResponse().getContentAsString(), "$.taskId").toString();

        mockMvc.perform(post("/api/projects/demo/local-execution/commands/%s/cancel".formatted(taskId))
                        .header(CURRENT_USER_HEADER, "user-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorId":"user-reviewer","note":"停止验证"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 写入本地文件缺少请求身份时返回401() throws Exception {
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"agent.md","content":"执行说明","actorId":"user-dev"}
                                """.formatted(workspaceId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 写入本地文件请求身份和操作者不一致时返回403() throws Exception {
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/write")
                        .header(CURRENT_USER_HEADER, "user-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"agent.md","content":"执行说明","actorId":"user-dev"}
                                """.formatted(workspaceId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 写入本地文件缺少操作者时返回400() throws Exception {
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/write")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"agent.md","content":"执行说明"}
                                """.formatted(workspaceId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 写入本地文件请求身份和操作者一致时成功() throws Exception {
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/write")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"agent.md","content":"执行说明","actorId":"user-dev"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(workspaceId))
                .andExpect(jsonPath("$.relativePath").value("agent.md"))
                .andExpect(jsonPath("$.bytesWritten").isNumber());
    }

    @Test
    void 列出本地文件缺少请求身份时返回401() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "MatrixCode");
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"."}
                                """.formatted(workspaceId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能列出本地文件() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "MatrixCode");
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/list")
                        .header(CURRENT_USER_HEADER, "user-stranger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"."}
                                """.formatted(workspaceId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目成员可以列出本地文件() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        Files.writeString(workspace.resolve("README.md"), "MatrixCode");
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/list")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"."}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relativePath").value("README.md"));
    }

    @Test
    void 读取本地文件缺少请求身份时返回401() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "MatrixCode 本地执行代理");
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"README.md"}
                                """.formatted(workspaceId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能读取本地文件() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "MatrixCode 本地执行代理");
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/read")
                        .header(CURRENT_USER_HEADER, "user-stranger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"README.md"}
                                """.formatted(workspaceId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 采集GitDiff缺少请求身份时返回401() throws Exception {
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/git-diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s"}
                                """.formatted(workspaceId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能采集GitDiff() throws Exception {
        var workspaceId = authorizeWorkspace();

        mockMvc.perform(post("/api/projects/demo/local-execution/git-diff")
                        .header(CURRENT_USER_HEADER, "user-stranger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s"}
                                """.formatted(workspaceId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 本地执行代理接口可以串起授权读取命令和摘要() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        Files.writeString(workspace.resolve("README.md"), "MatrixCode 本地执行代理");
        var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"当前项目","rootPath":"%s"}
                                """.formatted(escapePath(workspace))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("当前项目"))
                .andReturn();
        var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/read")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"README.md"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("MatrixCode 本地执行代理"));

        var commandResponse = mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-ops","command":"ssh prod systemctl restart app"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVAL_PENDING"))
                .andReturn();
        var pendingTaskId = JsonPath.read(commandResponse.getResponse().getContentAsString(), "$.taskId").toString();

        mockMvc.perform(post("/api/projects/demo/local-execution/git-diff")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repository").value(false));

        mockMvc.perform(get("/api/projects/demo/local-execution/summary")
                        .header(CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaces[0].id").value(workspaceId))
                .andExpect(jsonPath("$.recentFileOperations[0].relativePath").value("README.md"))
                .andExpect(jsonPath("$.recentTasks[0].status").value("APPROVAL_PENDING"))
                .andExpect(jsonPath("$.recentTasks[0].taskId").value(pendingTaskId))
                .andExpect(jsonPath("$.recentAuditRecords[0].decision").value("ASK"))
                .andExpect(jsonPath("$.activeTasks").isArray())
                .andExpect(jsonPath("$.activeTasks").isEmpty())
                .andExpect(jsonPath("$.recentTaskLogs").isArray());
    }

    @Test
    void 路径逃逸通过接口返回中文错误() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"当前项目","rootPath":"%s"}
                                """.formatted(escapePath(workspace))))
                .andExpect(status().isOk())
                .andReturn();
        var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/read")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"../secret.txt"}
                                """.formatted(workspaceId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("路径不能离开授权工作区")));
    }

    @Test
    void 可以通过接口拒绝待审批任务并在摘要中看到结果() throws Exception {
        var workspaceId = authorizeWorkspace();
        var commandResponse = mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-dev","command":"git status"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVAL_PENDING"))
                .andReturn();
        var taskId = JsonPath.read(commandResponse.getResponse().getContentAsString(), "$.taskId").toString();

        mockMvc.perform(post("/api/projects/demo/local-execution/commands/%s/approval".formatted(taskId))
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorId":"user-reviewer","decision":"DENY","note":"本轮不执行"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"))
                .andExpect(jsonPath("$.approvalDecision").value("DENY"))
                .andExpect(jsonPath("$.approverId").value("user-reviewer"))
                .andExpect(jsonPath("$.approvalNote").value("本轮不执行"));

        mockMvc.perform(get("/api/projects/demo/local-execution/summary")
                        .header(CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentTasks[0].taskId").value(taskId))
                .andExpect(jsonPath("$.recentTasks[0].status").value("DENIED"))
                .andExpect(jsonPath("$.recentAuditRecords[0].decision").value("DENY"));
    }

    @Test
    void 可以通过接口取消本地长任务并查询日志() throws Exception {
        var workspaceId = authorizeWorkspace();
        var commandResponse = mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-dev","command":"sleep 5"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVAL_PENDING"))
                .andReturn();
        var taskId = JsonPath.read(commandResponse.getResponse().getContentAsString(), "$.taskId").toString();

        mockMvc.perform(post("/api/projects/demo/local-execution/commands/%s/approval".formatted(taskId))
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorId":"user-reviewer","decision":"ALLOW","note":"允许运行长任务"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        mockMvc.perform(post("/api/projects/demo/local-execution/commands/%s/cancel".formatted(taskId))
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorId":"user-reviewer","note":"停止验证"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.canceledBy").value("user-reviewer"))
                .andExpect(jsonPath("$.cancelNote").value("停止验证"));

        mockMvc.perform(get("/api/projects/demo/local-execution/commands/%s/logs".formatted(taskId))
                        .header(CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value(taskId))
                .andExpect(jsonPath("$[0].stream").value("SYSTEM"))
                .andExpect(jsonPath("$[0].content").value(containsString("任务已取消")));

        mockMvc.perform(get("/api/projects/demo/local-execution/summary")
                        .header(CURRENT_USER_HEADER, "user-dev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentTasks[0].taskId").value(taskId))
                .andExpect(jsonPath("$.recentTaskLogs[0].taskId").value(taskId))
                .andExpect(jsonPath("$.recentTaskLogs[0].content").value(containsString("任务已取消")));
    }

    @Test
    void 审批接口会拒绝重复处理() throws Exception {
        var workspaceId = authorizeWorkspace();
        var commandResponse = mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-dev","command":"git status"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andReturn();
        var taskId = JsonPath.read(commandResponse.getResponse().getContentAsString(), "$.taskId").toString();
        var decisionBody = """
                {"actorId":"user-reviewer","decision":"DENY","note":"拒绝"}
                """;

        mockMvc.perform(post("/api/projects/demo/local-execution/commands/%s/approval".formatted(taskId))
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/projects/demo/local-execution/commands/%s/approval".formatted(taskId))
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("任务已完成审批，不能重复处理")));
    }

    private String escapePath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private String authorizeWorkspace() throws Exception {
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"当前项目","rootPath":"%s"}
                                """.formatted(escapePath(workspace))))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();
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
