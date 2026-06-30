package com.matrixcode.workbench;

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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        MatrixCodeServerApplication.class,
        WorkbenchControllerTest.IdentityRepositoryTestConfig.class
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkbenchControllerTest {

    private static final Path LOCAL_EXECUTION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-local-execution-workbench-" + System.nanoTime() + ".json");
    private static final Path RUNTIME_NOTIFICATION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-runtime-notifications-" + System.nanoTime() + ".json");
    private static final Path WORKBENCH_STATE_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-workbench-state-workbench-" + System.nanoTime() + ".json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeProjectIdentityRepository repository;

    @TempDir
    Path workspace;

    @DynamicPropertySource
    static void runtimeNotificationProperties(DynamicPropertyRegistry registry) {
        registry.add("matrixcode.local-execution.storage-path", LOCAL_EXECUTION_STORAGE::toString);
        registry.add("matrixcode.runtime-notifications.storage-path", RUNTIME_NOTIFICATION_STORAGE::toString);
        registry.add("matrixcode.workbench-state.storage-path", WORKBENCH_STATE_STORAGE::toString);
    }

    @AfterEach
    void cleanRuntimeNotificationStorage() throws Exception {
        Files.deleteIfExists(LOCAL_EXECUTION_STORAGE);
        Files.deleteIfExists(RUNTIME_NOTIFICATION_STORAGE);
        Files.deleteIfExists(WORKBENCH_STATE_STORAGE);
    }

    @Test
    void 产品草稿冻结开发交付Bug和部署目标可以通过接口串起来() throws Exception {
        repository.ensureMember(member("demo", "user-product", "PRODUCT"));
        repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
        repository.ensureMember(member("demo", "user-tester", "TESTER"));
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        var draftsResponse = mockMvc.perform(post("/api/projects/demo/roles/product/drafts")
                        .header(CURRENT_USER_HEADER, "user-product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"支付失败后允许用户重新发起支付。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("产品需求草稿"))
                .andReturn();
        var documentId = JsonPath.read(draftsResponse.getResponse().getContentAsString(), "$[0].id").toString();

        mockMvc.perform(get("/api/projects/demo/workbench"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("需求草稿"))
                .andExpect(jsonPath("$.documents[0].id").isNotEmpty());

        mockMvc.perform(post("/api/projects/demo/documents/" + documentId + "/freeze")
                        .header(CURRENT_USER_HEADER, "user-product"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FROZEN"));

        mockMvc.perform(post("/api/projects/demo/roles/developer/deliveries")
                        .header(CURRENT_USER_HEADER, "user-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspacePath":"/repo/payment","implementationNote":"完成失败态处理","selfTestResult":"测试通过","apiDoc":"GET /payments/{id}","databaseScript":"alter table payment add fail_reason varchar(255);","deploymentDoc":"docker compose up -d"}
                                """))
                .andExpect(status().isOk());

        var bugResponse = mockMvc.perform(post("/api/projects/demo/bugs")
                        .header(CURRENT_USER_HEADER, "user-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"失败原因为空","severity":"HIGH","steps":"支付失败","expected":"显示失败原因","actual":"为空白","createdByRole":"测试","currentOwnerRole":"开发"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andReturn();
        var bugId = JsonPath.read(bugResponse.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/projects/demo/bugs/" + bugId + "/transitions")
                        .header(CURRENT_USER_HEADER, "user-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nextStatus\":\"REGRESSION_PENDING\",\"note\":\"开发已修复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGRESSION_PENDING"));

        mockMvc.perform(post("/api/projects/demo/roles/tester/reports")
                        .header(CURRENT_USER_HEADER, "user-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report\":\"核心链路通过，等待产品验收\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("测试报告"));

        mockMvc.perform(post("/api/projects/demo/deployments/targets")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"environmentName":"测试环境","environmentUrl":"https://test.example.com","sshAddress":"deploy@example.com","deployNote":"按部署文档执行","healthCheckUrl":"https://test.example.com/health","rollbackNote":"回滚上一版本"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remoteExecuted").value(false));
    }

    @Test
    void 产品可以通过接口提交验收记录() throws Exception {
        repository.ensureMember(member("demo", "user-product", "PRODUCT"));
        mockMvc.perform(post("/api/projects/demo/roles/product/acceptance")
                        .header(CURRENT_USER_HEADER, "user-product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\":true,\"note\":\"验收通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("产品验收记录"));
    }

    @Test
    void 可以通过接口运行健康检查并记录部署和回滚操作() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        repository.ensureMember(member("demo", "user-reviewer", "TESTER"));
        var targetResponse = mockMvc.perform(post("/api/projects/demo/deployments/targets")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"environmentName":"测试环境","environmentUrl":"https://test.example.com","sshAddress":"deploy@example.com","deployNote":"按部署文档执行","healthCheckUrl":"http://127.0.0.1:1/health","rollbackNote":"回滚上一版本"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        var targetId = JsonPath.read(targetResponse.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/projects/demo/deployments/targets/" + targetId + "/health-checks")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"user-ops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetId").value(targetId))
                .andExpect(jsonPath("$.actorId").value("user-ops"))
                .andExpect(jsonPath("$.status").value("UNREACHABLE"));

        mockMvc.perform(post("/api/projects/demo/deployments/targets/" + targetId + "/operations")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorId":"user-ops","type":"DEPLOYMENT","status":"SUCCEEDED","note":"预发部署完成"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetId").value(targetId))
                .andExpect(jsonPath("$.type").value("DEPLOYMENT"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        mockMvc.perform(post("/api/projects/demo/deployments/targets/" + targetId + "/operations")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorId":"user-ops","type":"ROLLBACK","status":"RECORDED","note":"记录回滚方案"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetId").value(targetId))
                .andExpect(jsonPath("$.type").value("ROLLBACK"))
                .andExpect(jsonPath("$.status").value("RECORDED"));

        mockMvc.perform(get("/api/projects/demo/workbench"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deploymentRuntimeSummaries[0].targetId").value(targetId))
                .andExpect(jsonPath("$.deploymentRuntimeSummaries[0].latestHealthCheck.actorId").value("user-ops"))
                .andExpect(jsonPath("$.deploymentRuntimeSummaries[0].latestHealthCheck.status").value("UNREACHABLE"))
                .andExpect(jsonPath("$.deploymentRuntimeSummaries[0].latestDeploymentOperation.note").value("预发部署完成"))
                .andExpect(jsonPath("$.deploymentRuntimeSummaries[0].latestRollbackOperation.note").value("记录回滚方案"));
    }

    @Test
    void 可以通过接口导入发布脚本审计到部署记录() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        var targetResponse = mockMvc.perform(post("/api/projects/demo/deployments/targets")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"environmentName":"生产环境","environmentUrl":"https://matrixcode.example.com","sshAddress":"deploy@example.com","deployNote":"按发布包安装","healthCheckUrl":"https://matrixcode.example.com/actuator/health","rollbackNote":"回滚上一版本"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        var targetId = JsonPath.read(targetResponse.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/projects/demo/deployments/targets/" + targetId + "/release-audit-imports")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorId": "user-ops",
                                  "sourceId": "rollback-audit.jsonl",
                                  "jsonLines": [
                                    "{\\"action\\":\\"rollback\\",\\"status\\":\\"SUCCEEDED\\",\\"occurredAt\\":\\"2026-06-29T09:00:00Z\\",\\"previousDir\\":\\"/opt/matrixcode.previous.20260629170000\\",\\"targetDir\\":\\"/opt/matrixcode\\",\\"failedDir\\":\\"/opt/matrixcode.failed.20260629170100\\"}"
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.records[0].type").value("ROLLBACK"))
                .andExpect(jsonPath("$.records[0].status").value("SUCCEEDED"));

        mockMvc.perform(get("/api/projects/demo/workbench")
                        .header(CURRENT_USER_HEADER, "user-ops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deploymentRuntimeSummaries[0].latestRollbackOperation.note",
                        containsString("发布脚本审计 rollback SUCCEEDED")));
    }

    @Test
    void 可以通过接口配置Compose演示环境并在工作台读取() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        Files.writeString(workspace.resolve("compose.yml"), "services:\n  web:\n    image: nginx:alpine\n");
        var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"演示工作区","rootPath":"%s"}
                                """.formatted(workspace.toString())))
                .andExpect(status().isOk())
                .andReturn();
        var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();
        var targetResponse = mockMvc.perform(post("/api/projects/demo/deployments/targets")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"environmentName":"演示环境","environmentUrl":"http://127.0.0.1:8080","sshAddress":"deploy@local","deployNote":"本地 Compose 演示","healthCheckUrl":"http://127.0.0.1:8080/health","rollbackNote":"停止演示服务"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        var targetId = JsonPath.read(targetResponse.getResponse().getContentAsString(), "$.id").toString();

        var composeResponse = mockMvc.perform(post("/api/projects/demo/deployments/targets/" + targetId + "/compose-environments")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","composeFilePath":"compose.yml","projectName":"matrixcode-demo","serviceName":"web"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetId").value(targetId))
                .andExpect(jsonPath("$.workspaceId").value(workspaceId))
                .andExpect(jsonPath("$.status").value("CONFIGURED"))
                .andReturn();
        var environmentId = JsonPath.read(composeResponse.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(get("/api/projects/demo/workbench"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.composeEnvironments[0].id").value(environmentId))
                .andExpect(jsonPath("$.composeRuntimeViews[0].environmentId").value(environmentId))
                .andExpect(jsonPath("$.composeRuntimeViews[0].projectName").value("matrixcode-demo"));
    }

    @Test
    void 配置部署目标缺少请求身份时返回401() throws Exception {
        mockMvc.perform(post("/api/projects/demo/deployments/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deploymentTargetJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能配置部署目标() throws Exception {
        mockMvc.perform(post("/api/projects/demo/deployments/targets")
                        .header(CURRENT_USER_HEADER, "user-outsider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deploymentTargetJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 项目成员可以配置部署目标() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));

        mockMvc.perform(post("/api/projects/demo/deployments/targets")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deploymentTargetJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remoteExecuted").value(false));
    }

    @Test
    void 部署健康检查缺少请求身份时返回401() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        var targetId = createDeploymentTarget("user-ops");

        mockMvc.perform(post("/api/projects/demo/deployments/targets/" + targetId + "/health-checks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"user-ops\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 部署健康检查请求身份和操作者不一致时返回403() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        repository.ensureMember(member("demo", "user-reviewer", "TESTER"));
        var targetId = createDeploymentTarget("user-ops");

        mockMvc.perform(post("/api/projects/demo/deployments/targets/" + targetId + "/health-checks")
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"user-ops\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 部署操作请求身份和操作者不一致时返回403() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        repository.ensureMember(member("demo", "user-reviewer", "TESTER"));
        var targetId = createDeploymentTarget("user-ops");

        mockMvc.perform(post("/api/projects/demo/deployments/targets/" + targetId + "/operations")
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorId":"user-ops","type":"DEPLOYMENT","status":"SUCCEEDED","note":"预发部署完成"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 非项目成员不能配置Compose演示环境() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        Files.writeString(workspace.resolve("compose.yml"), "services:\n  web:\n    image: nginx:alpine\n");
        var workspaceId = createLocalWorkspace("user-ops");
        var targetId = createDeploymentTarget("user-ops");

        mockMvc.perform(post("/api/projects/demo/deployments/targets/" + targetId + "/compose-environments")
                        .header(CURRENT_USER_HEADER, "user-outsider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","composeFilePath":"compose.yml","projectName":"matrixcode-demo","serviceName":"web"}
                                """.formatted(workspaceId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void Compose运行态动作缺少请求身份时返回401() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        var environmentId = createComposeEnvironment("user-ops");

        for (var action : List.of("validate", "start", "stop", "logs")) {
            mockMvc.perform(post("/api/projects/demo/compose-environments/" + environmentId + "/" + action)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actorId\":\"user-ops\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void 非项目成员不能执行Compose运行态动作() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        var environmentId = createComposeEnvironment("user-ops");

        for (var action : List.of("validate", "start", "stop", "logs")) {
            mockMvc.perform(post("/api/projects/demo/compose-environments/" + environmentId + "/" + action)
                            .header(CURRENT_USER_HEADER, "user-outsider")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actorId\":\"user-outsider\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void Compose运行态动作请求身份和操作者不一致时返回403() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        repository.ensureMember(member("demo", "user-reviewer", "TESTER"));
        var environmentId = createComposeEnvironment("user-ops");

        for (var action : List.of("validate", "start", "stop", "logs")) {
            mockMvc.perform(post("/api/projects/demo/compose-environments/" + environmentId + "/" + action)
                            .header(CURRENT_USER_HEADER, "user-reviewer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actorId\":\"user-ops\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void 项目成员可以执行Compose运行态校验() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        var environmentId = createComposeEnvironment("user-ops");

        mockMvc.perform(post("/api/projects/demo/compose-environments/" + environmentId + "/validate")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"user-ops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environmentId").value(environmentId))
                .andExpect(jsonPath("$.actorId").value("user-ops"))
                .andExpect(jsonPath("$.type").value("VALIDATE"));
    }

    @Test
    void 旧工作流写入口缺少请求身份时返回401() throws Exception {
        repository.ensureMember(member("demo", "user-product", "PRODUCT"));
        repository.ensureMember(member("demo", "user-tester", "TESTER"));
        var documentId = createProductDraft("user-product");
        var bugId = createBug("user-tester");

        mockMvc.perform(post("/api/projects/demo/roles/product/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"支付失败后允许用户重新发起支付。\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/projects/demo/documents/" + documentId + "/freeze"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/projects/demo/roles/developer/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(developerDeliveryJson()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/projects/demo/bugs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bugJson()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/projects/demo/bugs/" + bugId + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nextStatus\":\"REGRESSION_PENDING\",\"note\":\"开发已修复\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/projects/demo/roles/tester/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report\":\"核心链路通过，等待产品验收\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/projects/demo/roles/product/acceptance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\":true,\"note\":\"验收通过\",\"returnToRole\":\"开发\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 非项目成员不能写入旧工作流入口() throws Exception {
        repository.ensureMember(member("demo", "user-product", "PRODUCT"));
        repository.ensureMember(member("demo", "user-tester", "TESTER"));
        var documentId = createProductDraft("user-product");
        var bugId = createBug("user-tester");

        mockMvc.perform(post("/api/projects/demo/roles/product/drafts")
                        .header(CURRENT_USER_HEADER, "user-outsider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"支付失败后允许用户重新发起支付。\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/demo/documents/" + documentId + "/freeze")
                        .header(CURRENT_USER_HEADER, "user-outsider"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/demo/roles/developer/deliveries")
                        .header(CURRENT_USER_HEADER, "user-outsider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(developerDeliveryJson()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/demo/bugs")
                        .header(CURRENT_USER_HEADER, "user-outsider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bugJson()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/demo/bugs/" + bugId + "/transitions")
                        .header(CURRENT_USER_HEADER, "user-outsider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nextStatus\":\"REGRESSION_PENDING\",\"note\":\"开发已修复\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/demo/roles/tester/reports")
                        .header(CURRENT_USER_HEADER, "user-outsider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"report\":\"核心链路通过，等待产品验收\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/demo/roles/product/acceptance")
                        .header(CURRENT_USER_HEADER, "user-outsider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\":true,\"note\":\"验收通过\",\"returnToRole\":\"开发\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 运行态提醒已读缺少请求身份时返回401() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        var notificationId = createApprovalNotification("user-ops");

        mockMvc.perform(post("/api/projects/demo/runtime-notifications/" + notificationId + "/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorUserId\":\"user-ops\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/projects/demo/runtime-notifications/read-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorUserId\":\"user-ops\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 运行态提醒请求身份和已读操作者不一致时返回403() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        repository.ensureMember(member("demo", "user-reviewer", "TESTER"));
        var notificationId = createApprovalNotification("user-ops");

        mockMvc.perform(post("/api/projects/demo/runtime-notifications/" + notificationId + "/read")
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorUserId\":\"user-ops\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/demo/runtime-notifications/read-all")
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorUserId\":\"user-ops\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 运行态提醒省略请求体时使用请求身份标记已读() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        repository.ensureMember(member("demo", "user-reviewer", "TESTER"));
        var notificationId = createApprovalNotification("user-ops");

        mockMvc.perform(post("/api/projects/demo/runtime-notifications/" + notificationId + "/read")
                        .header(CURRENT_USER_HEADER, "user-reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readByUserId").value("user-reviewer"));

        mockMvc.perform(post("/api/projects/demo/runtime-notifications/read-all")
                        .header(CURRENT_USER_HEADER, "user-reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].readByUserId").value("user-reviewer"));
    }

    @Test
    void 工作台返回运行态提醒并支持标记已读() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        repository.ensureMember(member("demo", "user-reviewer", "TESTER"));
        var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"当前项目","rootPath":"%s"}
                                """.formatted(workspace.toString())))
                .andExpect(status().isOk())
                .andReturn();
        var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();
        var taskResponse = mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-ops","command":"git status"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andReturn();
        var taskId = JsonPath.read(taskResponse.getResponse().getContentAsString(), "$.taskId").toString();
        var notificationId = "approval:" + taskId;

        mockMvc.perform(get("/api/projects/demo/workbench"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtimeNotifications[0].id").value(notificationId))
                .andExpect(jsonPath("$.runtimeNotifications[0].readAt").value(nullValue()));

        mockMvc.perform(post("/api/projects/demo/runtime-notifications/" + notificationId + "/read")
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorUserId\":\"user-reviewer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(notificationId))
                .andExpect(jsonPath("$.readAt").isNotEmpty())
                .andExpect(jsonPath("$.readByUserId").value("user-reviewer"));

        mockMvc.perform(post("/api/projects/demo/runtime-notifications/" + notificationId + "/read")
                        .header(CURRENT_USER_HEADER, "user-reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(notificationId))
                .andExpect(jsonPath("$.readAt").isNotEmpty());

        mockMvc.perform(get("/api/projects/demo/workbench"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtimeNotifications[0].readAt").value(nullValue()));

        mockMvc.perform(get("/api/projects/demo/workbench")
                        .header(CURRENT_USER_HEADER, "user-reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtimeNotifications[0].readAt").isNotEmpty());
    }

    @Test
    void 可以批量标记当前项目运行态提醒已读() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        repository.ensureMember(member("demo", "user-reviewer", "TESTER"));
        var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"当前项目","rootPath":"%s"}
                                """.formatted(workspace.toString())))
                .andExpect(status().isOk())
                .andReturn();
        var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();
        mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-ops","command":"git status"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/demo/workbench"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtimeNotifications[0].readAt").value(nullValue()));

        mockMvc.perform(post("/api/projects/demo/runtime-notifications/read-all")
                        .header(CURRENT_USER_HEADER, "user-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorUserId\":\"user-reviewer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].readAt").isNotEmpty())
                .andExpect(jsonPath("$[0].readByUserId").value("user-reviewer"));

        mockMvc.perform(post("/api/projects/demo/runtime-notifications/read-all")
                        .header(CURRENT_USER_HEADER, "user-reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].readAt").isNotEmpty());

        mockMvc.perform(get("/api/projects/demo/workbench"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtimeNotifications[0].readAt").value(nullValue()));

        mockMvc.perform(get("/api/projects/demo/workbench")
                        .header(CURRENT_USER_HEADER, "user-reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtimeNotifications[0].readAt").isNotEmpty());
    }

    @Test
    void 批量已读会写入运行态提醒存储文件() throws Exception {
        repository.ensureMember(member("demo", "user-ops", "OPERATIONS"));
        repository.ensureMember(member("demo", "user-reviewer", "TESTER"));
        var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"当前项目","rootPath":"%s"}
                                """.formatted(workspace.toString())))
                .andExpect(status().isOk())
                .andReturn();
        var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();
        mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, "user-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-ops","command":"git status"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/demo/workbench"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/demo/runtime-notifications/read-all")
                        .header(CURRENT_USER_HEADER, "user-reviewer"))
                .andExpect(status().isOk());

        assertThat(Files.exists(RUNTIME_NOTIFICATION_STORAGE)).isTrue();
        assertThat(Files.readString(RUNTIME_NOTIFICATION_STORAGE)).contains("approval:").contains("readAt");
    }

    @Test
    void 空项目批量标记运行态提醒已读时返回空数组() throws Exception {
        repository.ensureMember(member("empty", "user-reviewer", "TESTER"));

        mockMvc.perform(post("/api/projects/empty/runtime-notifications/read-all")
                        .header(CURRENT_USER_HEADER, "user-reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void 标记不存在的运行态提醒已读时返回中文错误() throws Exception {
        repository.ensureMember(member("demo", "user-reviewer", "TESTER"));

        mockMvc.perform(post("/api/projects/demo/runtime-notifications/approval:missing/read")
                        .header(CURRENT_USER_HEADER, "user-reviewer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("运行态提醒不存在")));
    }

    @Test
    void 产品草稿缺少需求时返回中文错误信息() throws Exception {
        repository.ensureMember(member("demo", "user-product", "PRODUCT"));

        mockMvc.perform(post("/api/projects/demo/roles/product/drafts")
                        .header(CURRENT_USER_HEADER, "user-product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("产品需求不能为空")));
    }

    @Test
    void 创建Bug缺少严重级别时返回中文错误信息() throws Exception {
        repository.ensureMember(member("demo", "user-tester", "TESTER"));

        mockMvc.perform(post("/api/projects/demo/bugs")
                        .header(CURRENT_USER_HEADER, "user-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"失败原因为空","steps":"支付失败","expected":"显示失败原因","actual":"为空白","createdByRole":"测试","currentOwnerRole":"开发"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Bug 严重级别不能为空")));
    }

    @Test
    void 创建Bug严重级别格式不正确时返回中文错误信息() throws Exception {
        repository.ensureMember(member("demo", "user-tester", "TESTER"));

        mockMvc.perform(post("/api/projects/demo/bugs")
                        .header(CURRENT_USER_HEADER, "user-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"失败原因为空","severity":"严重","steps":"支付失败","expected":"显示失败原因","actual":"为空白","createdByRole":"测试","currentOwnerRole":"开发"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("请求内容格式不正确")));
    }

    private ProjectMember member(String projectId, String userId, String roleKey) {
        var now = Instant.parse("2026-06-25T13:50:00Z");
        return new ProjectMember(projectId + ":" + userId + ":" + roleKey, projectId, userId, roleKey, "ACTIVE", now, now, now);
    }

    private String deploymentTargetJson() {
        return """
                {"environmentName":"测试环境","environmentUrl":"https://test.example.com","sshAddress":"deploy@example.com","deployNote":"按部署文档执行","healthCheckUrl":"https://test.example.com/health","rollbackNote":"回滚上一版本"}
                """;
    }

    private String developerDeliveryJson() {
        return """
                {"workspacePath":"/repo/payment","implementationNote":"完成失败态处理","selfTestResult":"测试通过","apiDoc":"GET /payments/{id}","databaseScript":"alter table payment add fail_reason varchar(255);","deploymentDoc":"docker compose up -d"}
                """;
    }

    private String bugJson() {
        return """
                {"title":"失败原因为空","severity":"HIGH","steps":"支付失败","expected":"显示失败原因","actual":"为空白","createdByRole":"测试","currentOwnerRole":"开发"}
                """;
    }

    private String createDeploymentTarget(String actorUserId) throws Exception {
        var targetResponse = mockMvc.perform(post("/api/projects/demo/deployments/targets")
                        .header(CURRENT_USER_HEADER, actorUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deploymentTargetJson()))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(targetResponse.getResponse().getContentAsString(), "$.id").toString();
    }

    private String createLocalWorkspace(String actorUserId) throws Exception {
        var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .header(CURRENT_USER_HEADER, actorUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"演示工作区","rootPath":"%s"}
                                """.formatted(workspace.toString())))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();
    }

    private String createComposeEnvironment(String actorUserId) throws Exception {
        Files.writeString(workspace.resolve("compose.yml"), "services:\n  web:\n    image: nginx:alpine\n");
        var workspaceId = createLocalWorkspace(actorUserId);
        var targetId = createDeploymentTarget(actorUserId);
        var composeResponse = mockMvc.perform(post("/api/projects/demo/deployments/targets/" + targetId + "/compose-environments")
                        .header(CURRENT_USER_HEADER, actorUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","composeFilePath":"compose.yml","projectName":"matrixcode-demo","serviceName":"web"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(composeResponse.getResponse().getContentAsString(), "$.id").toString();
    }

    private String createProductDraft(String actorUserId) throws Exception {
        var draftsResponse = mockMvc.perform(post("/api/projects/demo/roles/product/drafts")
                        .header(CURRENT_USER_HEADER, actorUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"支付失败后允许用户重新发起支付。\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(draftsResponse.getResponse().getContentAsString(), "$[0].id").toString();
    }

    private String createBug(String actorUserId) throws Exception {
        var bugResponse = mockMvc.perform(post("/api/projects/demo/bugs")
                        .header(CURRENT_USER_HEADER, actorUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bugJson()))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(bugResponse.getResponse().getContentAsString(), "$.id").toString();
    }

    private String createApprovalNotification(String actorUserId) throws Exception {
        var workspaceId = createLocalWorkspace(actorUserId);
        mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .header(CURRENT_USER_HEADER, actorUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"%s","command":"git status"}
                                """.formatted(workspaceId, actorUserId)))
                .andExpect(status().isOk())
                .andReturn();
        var workbenchResponse = mockMvc.perform(get("/api/projects/demo/workbench"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(workbenchResponse.getResponse().getContentAsString(), "$.runtimeNotifications[0].id").toString();
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
