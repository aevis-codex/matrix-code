# MatrixCode 编码智能体执行桥接实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 新增编码智能体执行准备 API，把编码任务协议映射到授权工作区、本地命令审批、测试任务和 Git diff 基线。

**架构：** 在 `codingagent` 模块内新增执行桥接服务和响应领域对象。桥接服务复用 `CodingAgentTaskService`、`WorkspaceRegistry`、`LocalCommandService` 和 `LocalGitDiffService`，不直接写文件，不直接批准命令。

**技术栈：** Java 21、Spring Boot 3.5、JUnit 5、MockMvc、AssertJ、既有本地执行与审批模块。

---

### 任务 1：执行桥接领域对象和服务

**文件：**
- 新增：`server/src/main/java/com/matrixcode/codingagent/domain/CodingAgentExecutionStatus.java`
- 新增：`server/src/main/java/com/matrixcode/codingagent/domain/CodingAgentExecutionStep.java`
- 新增：`server/src/main/java/com/matrixcode/codingagent/domain/CodingAgentExecutionPlan.java`
- 新增：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentExecutionService.java`
- 修改：`server/src/test/java/com/matrixcode/codingagent/CodingAgentTaskServiceTest.java`

- [x] **步骤 1：编写失败的服务测试**

在 `CodingAgentTaskServiceTest` 中新增两个用例：

```java
@Test
void 执行准备会绑定授权工作区提交测试命令并采集diff() {
    var harness = executionHarness();
    var workspace = harness.workspaces().authorize("demo", "当前项目", tempWorkspace.toString());

    var plan = harness.executionService().prepare(
            "demo",
            ModelRole.DEVELOPER,
            "实现登录接口",
            workspace.id(),
            "user-dev",
            "git status"
    );

    assertThat(plan.task().workspaceId()).isEqualTo(workspace.id());
    assertThat(plan.testCommandTask().status()).isEqualTo(ExecutionTaskStatus.APPROVAL_PENDING);
    assertThat(plan.gitDiffSummary().workspaceId()).isEqualTo(workspace.id());
    assertThat(plan.executionSteps()).filteredOn(step -> step.type() == CodingAgentStepType.CODE_EDIT)
            .singleElement()
            .satisfies(step -> {
                assertThat(step.status()).isEqualTo(CodingAgentExecutionStatus.APPROVAL_REQUIRED);
                assertThat(step.localTool()).isEqualTo("local-execution.files.write");
            });
    assertThat(plan.executionSteps()).filteredOn(step -> step.type() == CodingAgentStepType.TEST_COMMAND)
            .singleElement()
            .satisfies(step -> {
                assertThat(step.status()).isEqualTo(CodingAgentExecutionStatus.APPROVAL_REQUIRED);
                assertThat(step.referenceId()).isEqualTo(plan.testCommandTask().taskId());
            });
}

@Test
void 未授权工作区不能准备执行() {
    var harness = executionHarness();

    assertThatThrownBy(() -> harness.executionService().prepare(
            "demo",
            ModelRole.DEVELOPER,
            "实现登录接口",
            "missing-workspace",
            "user-dev",
            "git status"
    ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("工作区未授权");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentTaskServiceTest test
```

预期：FAIL，原因是执行桥接类型和服务还不存在。

- [x] **步骤 3：实现最少服务代码**

新增领域对象并实现 `CodingAgentExecutionService.prepare(...)`：

- 调用 `taskService.plan(...)` 生成任务。
- 调用 `workspaces.requireAuthorized(...)` 校验工作区。
- 调用 `commandService.submit(...)` 提交测试命令。
- 调用 `gitDiffService.capture(...)` 采集 diff。
- 根据步骤类型生成执行映射。

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentTaskServiceTest test
```

预期：PASS。

### 任务 2：执行准备 API

**文件：**
- 修改：`server/src/main/java/com/matrixcode/codingagent/api/CodingAgentController.java`
- 修改：`server/src/test/java/com/matrixcode/codingagent/CodingAgentControllerTest.java`

- [x] **步骤 1：编写失败的控制器测试**

在 `CodingAgentControllerTest` 中注入 `WorkspaceRegistry`，使用 `@TempDir` 授权临时工作区，新增用例：

```java
@Test
void 可以创建编码智能体执行准备报告() throws Exception {
    var workspace = workspaceRegistry.authorize("demo", "当前项目", tempDir.toString());

    mockMvc.perform(post("/api/projects/demo/roles/developer/coding-agent/execution-plans")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"goal":"实现登录接口","workspaceId":"%s","actorId":"user-dev","testCommand":"git status"}
                            """.formatted(workspace.id())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task.role").value("DEVELOPER"))
            .andExpect(jsonPath("$.testCommandTask.status").value("APPROVAL_PENDING"))
            .andExpect(jsonPath("$.executionSteps[3].type").value("CODE_EDIT"))
            .andExpect(jsonPath("$.executionSteps[3].status").value("APPROVAL_REQUIRED"))
            .andExpect(jsonPath("$.gitDiffSummary.workspaceId").value(workspace.id()));
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentControllerTest test
```

预期：FAIL，原因是接口还不存在。

- [x] **步骤 3：实现控制器接口**

在 `CodingAgentController` 中注入 `CodingAgentExecutionService`，新增 `POST /execution-plans`。

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentControllerTest test
```

预期：PASS。

### 任务 3：验证、图谱和提交

**文件：**
- 修改：Obsidian `MatrixCode/1 项目首页.md`
- 修改：Obsidian `MatrixCode/3 阶段索引.md`
- 修改：Obsidian `MatrixCode/6 验证与风险.md`
- 新增：Obsidian `MatrixCode/阶段成果/24 编码智能体执行桥接.md`

- [x] **步骤 1：运行服务端全量验证**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：PASS。

- [x] **步骤 2：运行代码卫生和密钥扫描**

运行：

```bash
git diff --check
```

预期：PASS；密钥扫描不得发现用户真实 API Key 或数据库密码片段。

- [x] **步骤 3：更新 Obsidian 图谱**

记录第 24 阶段完成内容、验证证据、剩余风险和下一阶段建议。

- [x] **步骤 4：提交**

```bash
git add .
git commit -m "feat: 增加编码智能体执行桥接"
```
