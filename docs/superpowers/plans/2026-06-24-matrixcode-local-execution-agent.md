# MatrixCode 第四阶段受控本地执行代理实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 构建第四阶段受控本地执行代理纵切，让项目可以授权本地工作区，在受限边界内读写文本文件、执行安全测试命令、采集 Git diff，并在工作台展示审批、审计和执行摘要。

**架构：** 服务端新增 `localexecution` 模块，复用现有 `approval`、`execution`、`realtime` 与 `workbench` 模块。桌面端扩展 API 类型和右侧指标栏，开发面板保留原有交付表单，第四阶段先通过 API 与摘要展示验证本地执行能力。

**技术栈：** Java 21、Spring Boot 3.5.15、JUnit 5、React 19.2.7、TypeScript 6.0.3、Vitest 4.1.9、本地 Maven `/Users/Masons/Ai/Maven` 和仓库 `/Users/Masons/Ai/Maven_Ai_Store`。

---

## 范围检查

本计划只实现受控本地执行代理纵切，不做真实 SSH 连接、远程部署、审批通过后的二次执行、登录鉴权、数据库持久化、任务取消或流式日志。所有自动化验证必须能在无外网、无 API Key、无 SSH 密钥时通过。

命令执行只使用 `ProcessBuilder`，不打开通用 Shell。审批结果为 `ASK` 或 `DENY` 的命令不启动进程。Git diff 只执行固定命令，不接收用户自定义 Git 参数。

## 文件结构

```text
server/src/main/java/com/matrixcode/localexecution/
├── api/
│   └── LocalExecutionController.java
├── application/
│   ├── LocalCommandService.java
│   ├── LocalExecutionSummaryService.java
│   ├── LocalFileService.java
│   ├── LocalGitDiffService.java
│   ├── PathGuard.java
│   └── WorkspaceRegistry.java
└── domain/
    ├── DirectoryEntry.java
    ├── ExecutionTask.java
    ├── ExecutionTaskStatus.java
    ├── FileOperationRecord.java
    ├── FileOperationType.java
    ├── FileReadResult.java
    ├── FileWriteResult.java
    ├── GitDiffSummary.java
    ├── LocalExecutionSummary.java
    ├── WorkspaceAuthorization.java
    └── WorkspaceStatus.java

server/src/test/java/com/matrixcode/localexecution/
├── LocalCommandServiceTest.java
├── LocalExecutionControllerTest.java
├── LocalFileServiceTest.java
├── LocalGitDiffServiceTest.java
├── PathGuardTest.java
├── WorkspaceRegistryTest.java
└── WorkbenchLocalExecutionTest.java

修改：
- server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java
- server/src/main/java/com/matrixcode/workbench/domain/ProjectWorkbench.java
- desktop/src/api/client.ts
- desktop/src/api/client.test.ts
- desktop/src/components/InspectorPanel.tsx
- desktop/src/test/App.test.tsx
- docs/development/local-run.md
- docs/superpowers/plans/2026-06-24-matrixcode-local-execution-agent.md
```

---

### 任务 1：实现授权工作区和路径守卫

**文件：**

- 创建：`server/src/test/java/com/matrixcode/localexecution/WorkspaceRegistryTest.java`
- 创建：`server/src/test/java/com/matrixcode/localexecution/PathGuardTest.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/WorkspaceStatus.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/WorkspaceAuthorization.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/WorkspaceRegistry.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/PathGuard.java`

- [x] **步骤 1：编写授权工作区失败测试**

创建 `WorkspaceRegistryTest`，覆盖存在目录可授权、空项目编号被拒绝、文件路径被拒绝、撤销后不能作为可用工作区：

```java
package com.matrixcode.localexecution;

import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.localexecution.domain.WorkspaceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void 可以授权存在的本地目录() {
        var registry = new WorkspaceRegistry();

        var workspace = registry.authorize("demo", "当前项目", tempDir.toString());

        assertThat(workspace.id()).isNotBlank();
        assertThat(workspace.projectId()).isEqualTo("demo");
        assertThat(workspace.name()).isEqualTo("当前项目");
        assertThat(workspace.rootPath()).isEqualTo(tempDir.toAbsolutePath().normalize().toString());
        assertThat(workspace.status()).isEqualTo(WorkspaceStatus.AUTHORIZED);
        assertThat(registry.requireAuthorized("demo", workspace.id()).id()).isEqualTo(workspace.id());
    }

    @Test
    void 授权工作区拒绝空项目和不存在路径() {
        var registry = new WorkspaceRegistry();

        assertThatThrownBy(() -> registry.authorize(" ", "当前项目", tempDir.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目编号不能为空");
        assertThatThrownBy(() -> registry.authorize("demo", "当前项目", tempDir.resolve("missing").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区路径必须是已存在目录");
    }

    @Test
    void 授权工作区拒绝文件路径() throws Exception {
        var registry = new WorkspaceRegistry();
        var file = Files.writeString(tempDir.resolve("README.md"), "说明");

        assertThatThrownBy(() -> registry.authorize("demo", "当前项目", file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区路径必须是已存在目录");
    }

    @Test
    void 撤销后不能作为授权工作区使用() {
        var registry = new WorkspaceRegistry();
        var workspace = registry.authorize("demo", "当前项目", tempDir.toString());

        registry.revoke("demo", workspace.id());

        assertThat(registry.list("demo")).extracting("status").containsExactly(WorkspaceStatus.REVOKED);
        assertThatThrownBy(() -> registry.requireAuthorized("demo", workspace.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作区未授权");
    }
}
```

- [x] **步骤 2：编写路径守卫失败测试**

创建 `PathGuardTest`，覆盖相对路径、绝对路径、`..`、符号链接逃逸和写入父目录校验：

```java
package com.matrixcode.localexecution;

import com.matrixcode.localexecution.application.PathGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathGuardTest {

    @TempDir
    Path workspace;

    @TempDir
    Path outside;

    @Test
    void 相对路径会被解析到工作区内() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/readme.md"), "内容");
        var guard = new PathGuard();

        var resolved = guard.resolveExisting(workspace.toString(), "docs/readme.md");

        assertThat(resolved).isEqualTo(workspace.resolve("docs/readme.md").toRealPath());
    }

    @Test
    void 拒绝绝对路径和路径穿越() {
        var guard = new PathGuard();

        assertThatThrownBy(() -> guard.resolveExisting(workspace.toString(), workspace.resolve("x").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能使用相对路径");
        assertThatThrownBy(() -> guard.resolveExisting(workspace.toString(), "../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径不能离开授权工作区");
    }

    @Test
    void 拒绝符号链接逃逸() throws Exception {
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(workspace.resolve("link.txt"), outside.resolve("secret.txt"));
        var guard = new PathGuard();

        assertThatThrownBy(() -> guard.resolveExisting(workspace.toString(), "link.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径不能离开授权工作区");
    }

    @Test
    void 写入新文件时父目录必须在工作区内() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        var guard = new PathGuard();

        assertThat(guard.resolveWritable(workspace.toString(), "docs/new.md"))
                .isEqualTo(workspace.resolve("docs/new.md").toAbsolutePath().normalize());
        assertThatThrownBy(() -> guard.resolveWritable(workspace.toString(), "missing/new.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("父目录不存在");
    }
}
```

- [x] **步骤 3：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkspaceRegistryTest,PathGuardTest test
```

预期：编译失败，错误包含 `WorkspaceRegistry`、`WorkspaceStatus` 或 `PathGuard` 不存在。

- [x] **步骤 4：实现授权工作区和路径守卫**

实现要点：

- `WorkspaceStatus` 枚举包含 `AUTHORIZED`、`REVOKED`。
- `WorkspaceAuthorization` record 字段为 `id`、`projectId`、`name`、`rootPath`、`status`、`createdAt`、`lastAccessedAt`。
- `WorkspaceRegistry` 使用内存 `ConcurrentHashMap<String, WorkspaceAuthorization>`，key 为工作区 ID。
- `authorize(projectId, name, rootPath)` 校验目录存在，保存绝对规范路径。
- `list(projectId)` 按创建时间返回项目内工作区。
- `requireAuthorized(projectId, workspaceId)` 校验存在、项目一致、状态为 `AUTHORIZED`，并刷新最近访问时间。
- `PathGuard.resolveExisting(rootPath, relativePath)` 和 `resolveWritable(rootPath, relativePath)` 只接受相对路径，并确保真实路径不逃逸。

- [x] **步骤 5：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkspaceRegistryTest,PathGuardTest test
```

预期：`WorkspaceRegistryTest` 和 `PathGuardTest` 通过。

- [x] **步骤 6：提交**

```bash
git add server/src/main/java/com/matrixcode/localexecution server/src/test/java/com/matrixcode/localexecution/WorkspaceRegistryTest.java server/src/test/java/com/matrixcode/localexecution/PathGuardTest.java
git commit -m "feat: 添加本地工作区授权和路径守卫"
```

---

### 任务 2：实现受限文件操作服务

**文件：**

- 创建：`server/src/test/java/com/matrixcode/localexecution/LocalFileServiceTest.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/DirectoryEntry.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/FileOperationType.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/FileOperationRecord.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/FileReadResult.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/FileWriteResult.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/LocalFileService.java`

- [x] **步骤 1：编写文件服务失败测试**

创建 `LocalFileServiceTest`，覆盖目录列表、读取文本、写入小文件、大文件拒绝、二进制拒绝和路径逃逸拒绝：

```java
package com.matrixcode.localexecution;

import com.matrixcode.localexecution.application.LocalFileService;
import com.matrixcode.localexecution.application.PathGuard;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileServiceTest {

    @TempDir
    Path workspace;

    @Test
    void 可以列目录并读取文本文件() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/readme.md"), "本地执行代理说明");
        var registry = new WorkspaceRegistry();
        var service = new LocalFileService(registry, new PathGuard());
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var entries = service.list("demo", authorized.id(), "docs");
        var read = service.read("demo", authorized.id(), "docs/readme.md");

        assertThat(entries).extracting("name").containsExactly("readme.md");
        assertThat(read.content()).isEqualTo("本地执行代理说明");
        assertThat(read.sizeBytes()).isGreaterThan(0);
    }

    @Test
    void 可以写入小型文本文件并记录摘要() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        var registry = new WorkspaceRegistry();
        var service = new LocalFileService(registry, new PathGuard());
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var result = service.write("demo", authorized.id(), "docs/agent.md", "执行说明");

        assertThat(Files.readString(workspace.resolve("docs/agent.md"))).isEqualTo("执行说明");
        assertThat(result.bytesWritten()).isGreaterThan(0);
        assertThat(service.recentOperations("demo")).extracting("relativePath").contains("docs/agent.md");
    }

    @Test
    void 拒绝读取大文件和二进制文件() throws Exception {
        Files.writeString(workspace.resolve("large.txt"), "甲".repeat(70000));
        Files.write(workspace.resolve("binary.bin"), new byte[] {0, 1, 2, 3});
        var registry = new WorkspaceRegistry();
        var service = new LocalFileService(registry, new PathGuard());
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        assertThatThrownBy(() -> service.read("demo", authorized.id(), "large.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件超过读取上限");
        assertThatThrownBy(() -> service.read("demo", authorized.id(), "binary.bin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持读取二进制文件");
    }

    @Test
    void 文件操作不能离开授权工作区() {
        var registry = new WorkspaceRegistry();
        var service = new LocalFileService(registry, new PathGuard());
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        assertThatThrownBy(() -> service.read("demo", authorized.id(), "../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径不能离开授权工作区");
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalFileServiceTest test
```

预期：编译失败，错误包含 `LocalFileService`、`DirectoryEntry` 或 `FileReadResult` 不存在。

- [x] **步骤 3：实现受限文件操作**

实现要点：

- 读取上限为 64 KB，写入上限为 128 KB。
- `DirectoryEntry` 字段为 `name`、`relativePath`、`directory`、`sizeBytes`。
- `FileReadResult` 字段为 `workspaceId`、`relativePath`、`content`、`sizeBytes`。
- `FileWriteResult` 字段为 `workspaceId`、`relativePath`、`bytesWritten`。
- `FileOperationRecord` 字段为 `id`、`projectId`、`workspaceId`、`type`、`relativePath`、`status`、`summary`、`createdAt`。
- `LocalFileService` 复用 `WorkspaceRegistry` 和 `PathGuard`，记录最近 20 条文件操作。
- 二进制判断使用前 1024 字节中是否包含 `0` 字节。

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalFileServiceTest,WorkspaceRegistryTest,PathGuardTest test
```

预期：文件服务、工作区和路径守卫测试通过。

- [x] **步骤 5：提交**

```bash
git add server/src/main/java/com/matrixcode/localexecution server/src/test/java/com/matrixcode/localexecution/LocalFileServiceTest.java
git commit -m "feat: 添加受限本地文件操作"
```

---

### 任务 3：实现审批驱动的本地命令执行

**文件：**

- 创建：`server/src/test/java/com/matrixcode/localexecution/LocalCommandServiceTest.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/ExecutionTaskStatus.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/ExecutionTask.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/LocalCommandService.java`

- [x] **步骤 1：编写命令服务失败测试**

创建 `LocalCommandServiceTest`，覆盖安全命令执行、危险命令待审批、空命令拒绝和审计脱敏：

```java
package com.matrixcode.localexecution;

import com.matrixcode.approval.application.ApprovalPolicy;
import com.matrixcode.approval.application.AuditService;
import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.localexecution.application.LocalCommandService;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.localexecution.domain.ExecutionTaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalCommandServiceTest {

    @TempDir
    Path workspace;

    @Test
    void 安全命令会被执行并记录结果() {
        var registry = new WorkspaceRegistry();
        var audit = new AuditService();
        var service = new LocalCommandService(registry, new ApprovalPolicy(), audit);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var task = service.submit("demo", authorized.id(), "user-dev", "npm test");

        assertThat(task.approvalDecision()).isEqualTo(ApprovalDecision.ALLOW);
        assertThat(task.status()).isIn(ExecutionTaskStatus.SUCCESS, ExecutionTaskStatus.FAILED);
        assertThat(task.exitCode()).isNotNull();
        assertThat(service.recentTasks("demo")).extracting("taskId").contains(task.taskId());
        assertThat(audit.records()).hasSize(1);
    }

    @Test
    void 危险命令只进入待审批不执行() {
        var registry = new WorkspaceRegistry();
        var audit = new AuditService();
        var service = new LocalCommandService(registry, new ApprovalPolicy(), audit);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var task = service.submit("demo", authorized.id(), "user-ops", "ssh prod systemctl restart app");

        assertThat(task.approvalDecision()).isEqualTo(ApprovalDecision.ASK);
        assertThat(task.status()).isEqualTo(ExecutionTaskStatus.APPROVAL_PENDING);
        assertThat(task.exitCode()).isNull();
        assertThat(audit.records().getFirst().summary()).isEqualTo("ssh prod systemctl restart app");
    }

    @Test
    void 空命令会被拒绝且不写入任务列表() {
        var registry = new WorkspaceRegistry();
        var service = new LocalCommandService(registry, new ApprovalPolicy(), new AuditService());
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        assertThatThrownBy(() -> service.submit("demo", authorized.id(), "user-dev", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("命令不能为空");
        assertThat(service.recentTasks("demo")).isEmpty();
    }

    @Test
    void 带凭证的未知命令进入待审批且审计摘要脱敏() {
        var registry = new WorkspaceRegistry();
        var audit = new AuditService();
        var service = new LocalCommandService(registry, new ApprovalPolicy(), audit);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var task = service.submit("demo", authorized.id(), "user-dev", "deploy --token sk-secret");

        assertThat(task.status()).isEqualTo(ExecutionTaskStatus.APPROVAL_PENDING);
        assertThat(audit.records().getFirst().summary()).isEqualTo("deploy --token ***");
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalCommandServiceTest test
```

预期：编译失败，错误包含 `LocalCommandService` 或 `ExecutionTaskStatus` 不存在。

- [x] **步骤 3：实现审批驱动命令执行**

实现要点：

- `ExecutionTaskStatus` 包含 `APPROVAL_PENDING`、`DENIED`、`RUNNING`、`SUCCESS`、`FAILED`。
- `ExecutionTask` 字段为 `taskId`、`projectId`、`workspaceId`、`actorId`、`toolType`、`command`、`approvalDecision`、`status`、`exitCode`、`stdoutSummary`、`stderrSummary`、`durationMillis`、`createdAt`。
- `LocalCommandService.submit(projectId, workspaceId, actorId, command)`：
  - 校验工作区授权。
  - 先通过 `ApprovalPolicy` 得到审批结果。
  - 使用 `AuditService.record(...)` 记录脱敏审计。
  - `ASK` 返回 `APPROVAL_PENDING`。
  - `DENY` 返回 `DENIED`。
  - `ALLOW` 使用 `ProcessBuilder` 执行。
- 不支持管道、重定向、命令连接符、后台符号、环境变量赋值和子 Shell；命中这些字符时创建 `ASK` 任务。
- 输出摘要最多 4096 字符，超出追加 `...`。
- 任务历史按项目保留最近 20 条。

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalCommandServiceTest,ApprovalPolicyTest test
```

预期：命令服务和审批策略测试通过。

- [x] **步骤 5：提交**

```bash
git add server/src/main/java/com/matrixcode/localexecution server/src/test/java/com/matrixcode/localexecution/LocalCommandServiceTest.java
git commit -m "feat: 添加审批驱动的本地命令执行"
```

---

### 任务 4：实现 Git diff 摘要采集

**文件：**

- 创建：`server/src/test/java/com/matrixcode/localexecution/LocalGitDiffServiceTest.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/GitDiffSummary.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/LocalGitDiffService.java`

- [x] **步骤 1：编写 Git diff 失败测试**

创建 `LocalGitDiffServiceTest`，使用 `git init` 创建临时仓库，覆盖仓库和非仓库两种情况：

```java
package com.matrixcode.localexecution;

import com.matrixcode.localexecution.application.LocalGitDiffService;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LocalGitDiffServiceTest {

    @TempDir
    Path workspace;

    @Test
    void Git仓库返回变更文件和统计摘要() throws Exception {
        run("git", "init");
        run("git", "config", "user.email", "matrixcode@example.com");
        run("git", "config", "user.name", "MatrixCode Test");
        Files.writeString(workspace.resolve("README.md"), "初始内容\n");
        run("git", "add", "README.md");
        run("git", "commit", "-m", "init");
        Files.writeString(workspace.resolve("README.md"), "初始内容\n新增内容\n");
        var registry = new WorkspaceRegistry();
        var service = new LocalGitDiffService(registry);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var summary = service.capture("demo", authorized.id());

        assertThat(summary.repository()).isTrue();
        assertThat(summary.changedFiles()).containsExactly("README.md");
        assertThat(summary.stat()).contains("README.md");
    }

    @Test
    void 非Git目录返回非仓库状态() {
        var registry = new WorkspaceRegistry();
        var service = new LocalGitDiffService(registry);
        var authorized = registry.authorize("demo", "当前项目", workspace.toString());

        var summary = service.capture("demo", authorized.id());

        assertThat(summary.repository()).isFalse();
        assertThat(summary.changedFiles()).isEmpty();
    }

    private void run(String... command) throws Exception {
        var process = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(0);
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalGitDiffServiceTest test
```

预期：编译失败，错误包含 `LocalGitDiffService` 或 `GitDiffSummary` 不存在。

- [x] **步骤 3：实现 Git diff 服务**

实现要点：

- `GitDiffSummary` 字段为 `projectId`、`workspaceId`、`repository`、`changedFiles`、`stat`、`capturedAt`。
- `LocalGitDiffService.capture(projectId, workspaceId)`：
  - 校验工作区授权。
  - 如果 `.git` 不存在，返回 `repository=false`。
  - 执行固定命令 `git diff --name-only` 和 `git diff --stat`。
  - 文件列表按输出顺序返回，空行过滤。
  - 最近摘要按项目保留。

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalGitDiffServiceTest test
```

预期：Git diff 服务测试通过。

- [x] **步骤 5：提交**

```bash
git add server/src/main/java/com/matrixcode/localexecution server/src/test/java/com/matrixcode/localexecution/LocalGitDiffServiceTest.java
git commit -m "feat: 添加本地 Git diff 摘要采集"
```

---

### 任务 5：接入 REST API 和工作台聚合

**文件：**

- 创建：`server/src/test/java/com/matrixcode/localexecution/LocalExecutionControllerTest.java`
- 创建：`server/src/test/java/com/matrixcode/localexecution/WorkbenchLocalExecutionTest.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/domain/LocalExecutionSummary.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/application/LocalExecutionSummaryService.java`
- 创建：`server/src/main/java/com/matrixcode/localexecution/api/LocalExecutionController.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/domain/ProjectWorkbench.java`

- [x] **步骤 1：编写 REST 和工作台失败测试**

创建 `LocalExecutionControllerTest`，串起工作区授权、文件读取、危险命令待审批、Git diff 和摘要接口：

```java
package com.matrixcode.localexecution;

import com.jayway.jsonpath.JsonPath;
import com.matrixcode.MatrixCodeServerApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = MatrixCodeServerApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LocalExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path workspace;

    @Test
    void 本地执行代理接口可以串起授权读取命令和摘要() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "MatrixCode 本地执行代理");
        var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"当前项目","rootPath":"%s"}
                                """.formatted(workspace.toString().replace("\\", "\\\\"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("当前项目"))
                .andReturn();
        var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"README.md"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("MatrixCode 本地执行代理"));

        mockMvc.perform(post("/api/projects/demo/local-execution/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","actorId":"user-ops","command":"ssh prod systemctl restart app"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVAL_PENDING"));

        mockMvc.perform(post("/api/projects/demo/local-execution/git-diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s"}
                                """.formatted(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repository").value(false));

        mockMvc.perform(get("/api/projects/demo/local-execution/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaces[0].id").value(workspaceId))
                .andExpect(jsonPath("$.recentFileOperations[0].relativePath").value("README.md"))
                .andExpect(jsonPath("$.recentTasks[0].status").value("APPROVAL_PENDING"))
                .andExpect(jsonPath("$.recentAuditRecords[0].decision").value("ASK"));
    }

    @Test
    void 路径逃逸通过接口返回中文错误() throws Exception {
        var workspaceResponse = mockMvc.perform(post("/api/projects/demo/local-execution/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"当前项目","rootPath":"%s"}
                                """.formatted(workspace.toString().replace("\\", "\\\\"))))
                .andExpect(status().isOk())
                .andReturn();
        var workspaceId = JsonPath.read(workspaceResponse.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/projects/demo/local-execution/files/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId":"%s","relativePath":"../secret.txt"}
                                """.formatted(workspaceId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("路径不能离开授权工作区")));
    }
}
```

创建 `WorkbenchLocalExecutionTest`，断言工作台聚合 `localExecution`：

```java
package com.matrixcode.localexecution;

import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.bug.application.BugService;
import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.document.application.DocumentService;
import com.matrixcode.context.application.ContextEngine;
import com.matrixcode.localexecution.application.LocalExecutionSummaryService;
import com.matrixcode.localexecution.application.WorkspaceRegistry;
import com.matrixcode.modelgateway.application.DeterministicModelAdapter;
import com.matrixcode.modelgateway.application.ModelGatewayService;
import com.matrixcode.modelgateway.application.ModelProviderRegistry;
import com.matrixcode.modelgateway.application.PromptCacheEstimator;
import com.matrixcode.modelgateway.application.PromptContractBuilder;
import com.matrixcode.modelgateway.application.RoleModelBindingService;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.usage.application.UsageCalculator;
import com.matrixcode.workbench.application.WorkbenchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchLocalExecutionTest {

    @TempDir
    Path workspace;

    @Test
    void 工作台返回本地执行代理摘要() {
        var events = new ProjectEventBus();
        var productDraftAgent = new LocalProductDraftAgent();
        var providers = new ModelProviderRegistry();
        var bindings = new RoleModelBindingService(providers);
        var modelGateway = new ModelGatewayService(
                providers,
                bindings,
                new PromptContractBuilder(),
                new PromptCacheEstimator(),
                new UsageCalculator(),
                new ContextEngine(),
                new DeterministicModelAdapter(productDraftAgent),
                events
        );
        var workspaceRegistry = new WorkspaceRegistry();
        workspaceRegistry.authorize("demo", "当前项目", workspace.toString());
        var service = new WorkbenchService(
                new DocumentService(),
                productDraftAgent,
                new BugService(),
                new DeploymentTargetService(),
                events,
                modelGateway,
                new LocalExecutionSummaryService(workspaceRegistry, null, null, null, null)
        );

        var workbench = service.get("demo");

        assertThat(workbench.localExecution().workspaces()).hasSize(1);
        assertThat(workbench.localExecution().workspaces().getFirst().name()).isEqualTo("当前项目");
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionControllerTest,WorkbenchLocalExecutionTest test
```

预期：编译失败，错误包含 `LocalExecutionController`、`LocalExecutionSummary` 或 `ProjectWorkbench.localExecution()` 不存在。

- [x] **步骤 3：实现 REST API 和工作台聚合**

实现要点：

- `LocalExecutionSummary` 字段为 `workspaces`、`recentFileOperations`、`recentTasks`、`recentGitDiff`、`recentAuditRecords`。
- `LocalExecutionSummaryService` 聚合工作区、文件操作、命令任务、Git diff 和审计记录；构造参数允许部分服务为空，测试中为空时返回空列表。
- `LocalExecutionController` 暴露规格中的 7 个项目级接口。
- `WorkbenchService` 构造函数新增 `LocalExecutionSummaryService`，`get()` 中调用 `localExecutionSummaryService.summary(projectId)`。
- `ProjectWorkbench` record 新增 `LocalExecutionSummary localExecution`，构造器校验非空。
- `LocalFileService` 写操作、`LocalCommandService` 命令状态、`LocalGitDiffService` 采集动作发布对应中文事件。

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalExecutionControllerTest,WorkbenchLocalExecutionTest,WorkbenchServiceTest,WorkbenchControllerTest test
```

预期：本地执行 API、工作台聚合和原有工作台回归测试通过。

- [x] **步骤 5：提交**

```bash
git add server/src/main/java/com/matrixcode/localexecution server/src/main/java/com/matrixcode/workbench server/src/test/java/com/matrixcode/localexecution server/src/test/java/com/matrixcode/workbench
git commit -m "feat: 接入本地执行代理工作台接口"
```

---

### 任务 6：扩展桌面 API 类型和本地执行指标栏

**文件：**

- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`
- 修改：`desktop/src/components/InspectorPanel.tsx`
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写桌面失败测试**

修改 `desktop/src/api/client.test.ts`，新增 API 客户端测试：

```ts
import {
  authorizeLocalWorkspace,
  loadLocalExecutionSummary,
  readLocalFile,
  submitLocalCommand,
  captureGitDiff
} from './client';

it('授权本地工作区时调用本地执行代理地址', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ id: 'workspace-1', name: '当前项目' })
  });
  vi.stubGlobal('fetch', fetchMock);

  await authorizeLocalWorkspace('demo', { name: '当前项目', rootPath: '/repo/matrixcode' }, 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/local-execution/workspaces', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: '当前项目', rootPath: '/repo/matrixcode' })
  });
});

it('读取本地执行摘要', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ workspaces: [], recentFileOperations: [], recentTasks: [], recentAuditRecords: [] })
  });
  vi.stubGlobal('fetch', fetchMock);

  await loadLocalExecutionSummary('demo', 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/local-execution/summary', {
    headers: { Accept: 'application/json' }
  });
});

it('提交本地命令和 Git diff 请求', async () => {
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ status: 'APPROVAL_PENDING' }) });
  vi.stubGlobal('fetch', fetchMock);

  await submitLocalCommand('demo', { workspaceId: 'workspace-1', actorId: 'user-dev', command: 'ssh prod' }, 'http://localhost:8080');
  await captureGitDiff('demo', { workspaceId: 'workspace-1' }, 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/local-execution/commands', expect.any(Object));
  expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/local-execution/git-diff', expect.any(Object));
});
```

修改 `desktop/src/test/App.test.tsx` 的基础工作台 mock，增加 `localExecution` 字段，并新增断言：

```ts
expect(screen.getByText('本地执行代理')).toBeTruthy();
expect(screen.getByText(/当前项目/)).toBeTruthy();
expect(screen.getByText(/APPROVAL_PENDING/)).toBeTruthy();
expect(screen.getByText(/Git diff/)).toBeTruthy();
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
cd desktop && npm test
```

预期：TypeScript 或 Vitest 失败，错误包含新增 API 导出、`localExecution` 类型或 UI 文案不存在。

- [x] **步骤 3：扩展桌面 API 类型和客户端函数**

在 `desktop/src/api/client.ts` 增加类型：

- `WorkspaceStatus`
- `WorkspaceAuthorization`
- `DirectoryEntry`
- `FileReadResult`
- `FileWriteResult`
- `ExecutionTaskStatus`
- `ExecutionTask`
- `FileOperationRecord`
- `GitDiffSummary`
- `LocalExecutionSummary`

新增函数：

- `authorizeLocalWorkspace(projectId, input, serverUrl)`
- `loadLocalExecutionSummary(projectId, serverUrl)`
- `listLocalFiles(projectId, input, serverUrl)`
- `readLocalFile(projectId, input, serverUrl)`
- `writeLocalFile(projectId, input, serverUrl)`
- `submitLocalCommand(projectId, input, serverUrl)`
- `captureGitDiff(projectId, input, serverUrl)`

并在 `ProjectWorkbench` 类型上增加 `localExecution: LocalExecutionSummary`。

- [x] **步骤 4：扩展右侧运行指标栏**

修改 `InspectorPanel`：

- props 增加 `localExecution`。
- 展示标题 `本地执行代理`。
- 展示第一条授权工作区名称和根路径。
- 展示最近命令状态、审批结果和命令摘要。
- 展示最近 Git diff 变更文件数。
- 展示最近审计摘要。
- 没有授权工作区时显示 `暂无授权工作区`。

修改 `App.tsx` 调用 `InspectorPanel` 的地方，传入 `workbench.localExecution`。

- [x] **步骤 5：运行桌面测试和构建验证通过**

运行：

```bash
cd desktop && npm test && npm run build
```

预期：桌面端测试和构建通过。

- [x] **步骤 6：提交**

```bash
git add desktop/src/api/client.ts desktop/src/api/client.test.ts desktop/src/components/InspectorPanel.tsx desktop/src/test/App.test.tsx desktop/src/App.tsx
git commit -m "feat: 展示本地执行代理指标"
```

---

### 任务 7：第四阶段整体验证和文档更新

**文件：**

- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-24-matrixcode-local-execution-agent.md`

- [x] **步骤 1：运行服务端全量测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：服务端全部测试通过。

- [x] **步骤 2：运行桌面端测试和构建**

运行：

```bash
cd desktop && npm test && npm run build && npm run tauri:build -- --help
```

预期：桌面端测试、TypeScript 构建、Vite 构建和 Tauri 命令入口通过。

- [x] **步骤 3：启动服务并验证本地执行代理接口**

启动服务端：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

另一个终端运行：

```bash
curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/workspaces \
  -H 'Content-Type: application/json' \
  -d '{"name":"MatrixCode 工作区","rootPath":"/Users/Masons/Ai/Codex/MatrixCode/.worktrees/mvp-vertical-slice"}'
curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/files/read \
  -H 'Content-Type: application/json' \
  -d '{"workspaceId":"替换为上一步返回的工作区 ID","relativePath":"README.md"}'
curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/commands \
  -H 'Content-Type: application/json' \
  -d '{"workspaceId":"替换为上一步返回的工作区 ID","actorId":"user-dev","command":"ssh prod systemctl restart app"}'
curl -sS -X POST http://localhost:8080/api/projects/demo/local-execution/git-diff \
  -H 'Content-Type: application/json' \
  -d '{"workspaceId":"替换为上一步返回的工作区 ID"}'
curl -sS http://localhost:8080/api/projects/demo/workbench
```

预期：

- 工作区授权接口返回 `AUTHORIZED`。
- 文件读取接口返回文本内容或明确的中文错误；路径逃逸请求返回 400。
- SSH 命令返回 `APPROVAL_PENDING`，不执行远程动作。
- Git diff 接口返回仓库状态和变更摘要。
- 工作台返回 `localExecution` 字段。

- [x] **步骤 4：更新本地运行文档**

在 `docs/development/local-run.md` 增加第四阶段本地执行代理验证说明，包括授权工作区、读取文件、提交待审批命令、采集 Git diff 和查看工作台。

- [x] **步骤 5：检查文档中文和 Maven 命令**

运行：

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|\\bplace(holder)\\b|\\bS[u]mmary\\b|\\bG[o]als\\b|\\bAcceptance C[r]iteria\\b" docs || true
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs || true
git diff --check
```

预期：未发现占位内容和未按本机路径书写的 Maven 命令；`git diff --check` 没有输出。若命中 `LocalExecutionSummary`，属于类型名误报。

- [x] **步骤 6：勾选计划状态并提交**

勾选本计划完成步骤，追加验证记录，并提交：

```bash
git add docs/development/local-run.md docs/superpowers/plans/2026-06-24-matrixcode-local-execution-agent.md
git commit -m "docs: 记录第四阶段本地执行代理验证"
```

---

## 自检记录

- 规格覆盖：工作区授权、路径守卫、受限文件操作、审批驱动命令、Git diff、工作台摘要、桌面展示和运行验证均有对应任务。
- 范围控制：真实 SSH、远程部署、审批通过后的二次执行、登录鉴权、数据库持久化、任务取消和流式日志不进入本计划。
- 安全边界：路径逃逸、符号链接逃逸、二进制读取、大文件读取、危险命令和凭证命令均在服务端测试中覆盖。
- 类型一致性：服务端使用 `WorkspaceAuthorization`、`ExecutionTask`、`GitDiffSummary`、`LocalExecutionSummary`；桌面端使用同名 TypeScript 类型。
- 验证命令：服务端命令均使用 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store`。

## 第四阶段验证记录

- 服务端全量测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过；Surefire 汇总为 `Tests run: 130, Failures: 0, Errors: 0, Skipped: 0`。
- 桌面端完整验证：`cd desktop && npm test && npm run build && npm run tauri:build -- --help` 通过；Vitest 结果为 `34` 个测试通过，TypeScript、Vite 构建和 Tauri 命令入口均通过。
- 运行态授权工作区：`POST /api/projects/demo/local-execution/workspaces` 返回 `AUTHORIZED`，授权根目录为 `/Users/Masons/Ai/Codex/MatrixCode/.worktrees/mvp-vertical-slice`。
- 运行态文件读取：`POST /api/projects/demo/local-execution/files/read` 读取 `docs/development/local-run.md` 成功，返回 `5837` 字节文本内容；路径逃逸请求 `../secret.txt` 返回 HTTP 400，错误信息为 `路径不能离开授权工作区`。
- 运行态命令审批：`POST /api/projects/demo/local-execution/commands` 提交 `ssh prod systemctl restart app` 后返回 `APPROVAL_PENDING`、`ASK`，`exitCode` 为空，确认没有执行远程动作。
- 运行态 Git diff：`POST /api/projects/demo/local-execution/git-diff` 返回 `repository=true` 和 `changedFiles=4`，并在 `git worktree` 工作区中正确识别 `.git` 文件形态；发现该缺口后新增 `GitWorktree的Git文件形态也会被识别为仓库` 回归测试，并将仓库识别从 `.git` 目录检查改为 `.git` 存在性检查。
- 运行态工作台：`GET /api/projects/demo/workbench` 返回 `localExecution`，聚合 `workspaces=1`、`tasks=1`、`audits=1`、`gitRepository=true`、`gitFiles=4`。
- 文档检查：占位内容扫描、未按本机路径书写的 Maven 命令扫描和 `git diff --check` 均通过。
