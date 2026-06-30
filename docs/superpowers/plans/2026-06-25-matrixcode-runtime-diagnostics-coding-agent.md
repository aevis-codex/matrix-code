# MatrixCode 真实联调诊断与编码智能体协议实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 新增真实运行诊断 API 和编码智能体任务协议 API，为真实联调和后续自动编码闭环建立可测试基础。

**架构：** 运行诊断模块只读取配置和做 TCP 探测，不读取或输出密钥。编码智能体模块只生成结构化任务计划，不执行命令、不改文件，后续阶段再接入本地执行与审批。

**技术栈：** Java 21、Spring Boot 3.5、JUnit 5、MockMvc、现有 MatrixCode 模块化单体。

**完成时间：** 2026-06-25

**最终验证证据：**
- `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test`：退出码 `0`，`278` 个测试，`0` failures，`0` errors，`0` skipped。
- `npm test`：退出码 `0`，Vitest `3` 个测试文件、`73` 个测试通过。
- `npm run build`：退出码 `0`，TypeScript 和 Vite 生产构建通过。
- `docker compose config`：退出码 `0`。
- `docker-compose config`：退出码 `0`。
- `ENV_FILE=.env.example MATRIXCODE_SKIP_CONNECTIVITY_CHECK=true scripts/check-real-runtime.sh`：退出码 `1`，按预期拒绝占位密钥。
- 将 `.env.example` 中 `change-me` 临时替换为测试值后执行 `scripts/check-real-runtime.sh`：退出码 `0`。
- `git diff --check`：退出码 `0`。
- 密钥扫描：未发现真实 API Key 或数据库密码片段。
- TCP 探测：历史内网地址上的 MySQL、Milvus、Redis、RocketMQ 当前均不可达，真实外部联调仍需网络或服务恢复后执行。

---

### 任务 1：运行诊断领域模型与服务

**文件：**
- 创建：`server/src/main/java/com/matrixcode/runtimecheck/domain/RuntimeCheckStatus.java`
- 创建：`server/src/main/java/com/matrixcode/runtimecheck/domain/RuntimeCheckItem.java`
- 创建：`server/src/main/java/com/matrixcode/runtimecheck/domain/RuntimeDiagnosticsReport.java`
- 创建：`server/src/main/java/com/matrixcode/runtimecheck/application/TcpConnectivityProbe.java`
- 创建：`server/src/main/java/com/matrixcode/runtimecheck/application/SocketTcpConnectivityProbe.java`
- 创建：`server/src/main/java/com/matrixcode/runtimecheck/application/RuntimeDiagnosticsService.java`
- 测试：`server/src/test/java/com/matrixcode/runtimecheck/RuntimeDiagnosticsServiceTest.java`

- [x] **步骤 1：编写失败测试**

```java
@Test
void jdbc模式缺少真实密码会返回阻塞失败() {
    var persistence = jdbcProperties("jdbc:mysql://<historical-host>:3306/matrixcode", "root", "change-me");
    var modelGateway = new ModelGatewayProperties();
    var service = new RuntimeDiagnosticsService(persistence, modelGateway, (host, port, timeout) -> true);

    var report = service.inspect();

    assertThat(report.status()).isEqualTo(RuntimeCheckStatus.FAIL);
    assertThat(report.items()).anySatisfy(item -> {
        assertThat(item.key()).isEqualTo("mysql.credentials");
        assertThat(item.blocking()).isTrue();
        assertThat(item.status()).isEqualTo(RuntimeCheckStatus.FAIL);
    });
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RuntimeDiagnosticsServiceTest test`
预期：FAIL，原因是 `runtimecheck` 模块尚不存在。

- [x] **步骤 3：实现最小领域模型和诊断服务**

创建三个 record/enum，服务检查 JDBC 模式、MySQL TCP、启用的模型供应商 API Key、Milvus 配置和 Redis/RocketMQ 预留项。

- [x] **步骤 4：运行聚焦测试**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RuntimeDiagnosticsServiceTest test`
预期：PASS。

### 任务 2：运行诊断 API

**文件：**
- 创建：`server/src/main/java/com/matrixcode/runtimecheck/api/RuntimeDiagnosticsController.java`
- 测试：`server/src/test/java/com/matrixcode/runtimecheck/RuntimeDiagnosticsControllerTest.java`

- [x] **步骤 1：编写失败测试**

```java
@Test
void 项目级运行诊断接口返回报告() throws Exception {
    mockMvc.perform(get("/api/projects/demo/runtime-diagnostics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").exists())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.nextActions").isArray());
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RuntimeDiagnosticsControllerTest test`
预期：FAIL，原因是接口尚不存在。

- [x] **步骤 3：实现控制器**

`GET /api/projects/{projectId}/runtime-diagnostics` 调用 `RuntimeDiagnosticsService.inspect()`，`projectId` 保留用于项目级路径一致性。

- [x] **步骤 4：运行聚焦测试**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=RuntimeDiagnosticsControllerTest,RuntimeDiagnosticsServiceTest test`
预期：PASS。

### 任务 3：编码智能体任务协议服务

**文件：**
- 创建：`server/src/main/java/com/matrixcode/codingagent/domain/CodingAgentTaskStatus.java`
- 创建：`server/src/main/java/com/matrixcode/codingagent/domain/CodingAgentStepType.java`
- 创建：`server/src/main/java/com/matrixcode/codingagent/domain/CodingAgentStep.java`
- 创建：`server/src/main/java/com/matrixcode/codingagent/domain/CodingAgentTask.java`
- 创建：`server/src/main/java/com/matrixcode/codingagent/application/CodingAgentTaskService.java`
- 测试：`server/src/test/java/com/matrixcode/codingagent/CodingAgentTaskServiceTest.java`

- [x] **步骤 1：编写失败测试**

```java
@Test
void 开发角色任务会生成可审查编码步骤() {
    var service = new CodingAgentTaskService(roleAgentConfigService);

    var task = service.plan("demo", ModelRole.DEVELOPER, "实现登录接口", "workspace-main");

    assertThat(task.status()).isEqualTo(CodingAgentTaskStatus.PLANNED);
    assertThat(task.steps()).extracting(CodingAgentStep::type)
            .containsExactly(
                    CodingAgentStepType.CONTEXT_RECALL,
                    CodingAgentStepType.PLAN_REVIEW,
                    CodingAgentStepType.FILE_REVIEW,
                    CodingAgentStepType.CODE_EDIT,
                    CodingAgentStepType.TEST_COMMAND,
                    CodingAgentStepType.DIFF_REVIEW,
                    CodingAgentStepType.HANDOFF
            );
    assertThat(task.steps()).filteredOn(step -> step.type() == CodingAgentStepType.TEST_COMMAND)
            .singleElement()
            .satisfies(step -> assertThat(step.requiresApproval()).isTrue());
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentTaskServiceTest test`
预期：FAIL，原因是编码智能体模块尚不存在。

- [x] **步骤 3：实现任务协议服务**

服务校验项目、角色、目标和工作区编号，读取角色智能体配置确保角色启用，然后返回固定协议步骤。步骤只描述工具建议，不执行工具。

- [x] **步骤 4：运行聚焦测试**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentTaskServiceTest test`
预期：PASS。

### 任务 4：编码智能体任务 API

**文件：**
- 创建：`server/src/main/java/com/matrixcode/codingagent/api/CodingAgentController.java`
- 测试：`server/src/test/java/com/matrixcode/codingagent/CodingAgentControllerTest.java`

- [x] **步骤 1：编写失败测试**

```java
@Test
void 可以创建开发角色编码智能体任务计划() throws Exception {
    mockMvc.perform(post("/api/projects/demo/roles/developer/coding-agent/tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"goal":"实现登录接口","workspaceId":"workspace-main"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("DEVELOPER"))
            .andExpect(jsonPath("$.steps[0].type").value("CONTEXT_RECALL"))
            .andExpect(jsonPath("$.steps[4].requiresApproval").value(true));
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentControllerTest test`
预期：FAIL，原因是接口尚不存在。

- [x] **步骤 3：实现控制器**

`POST /api/projects/{projectId}/roles/{role}/coding-agent/tasks` 将 `role` 转换为 `ModelRole`，调用 `CodingAgentTaskService.plan(...)`。

- [x] **步骤 4：运行聚焦测试**

运行：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=CodingAgentTaskServiceTest,CodingAgentControllerTest test`
预期：PASS。

### 任务 5：回溯、图谱和全量验证

**文件：**
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-runtime-diagnostics-coding-agent.md`
- 修改：Obsidian `MatrixCode/1 项目首页.md`
- 修改：Obsidian `MatrixCode/3 阶段索引.md`
- 修改：Obsidian `MatrixCode/6 验证与风险.md`
- 新增：Obsidian `MatrixCode/阶段成果/22 真实联调诊断与编码智能体协议.md`

- [x] **步骤 1：运行全量验证**

运行：
```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
cd desktop && npm test && npm run build
git diff --check
```

- [x] **步骤 2：运行密钥扫描**

扫描 `sk-`、真实 MySQL 密码片段和豆包 UUID 片段，预期无命中。

- [x] **步骤 3：更新 Obsidian 图谱**

记录第 22 阶段完成内容、验证证据、剩余风险和下一阶段建议。

- [x] **步骤 4：提交**

```bash
git add .
git commit -m "feat: 增加运行诊断和编码智能体协议"
```
