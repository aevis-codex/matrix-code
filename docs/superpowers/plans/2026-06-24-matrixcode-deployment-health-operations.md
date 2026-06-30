# MatrixCode 第六阶段部署健康检查与运维记录实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 构建第六阶段部署健康检查与运维记录纵切，让运维可以对已配置部署目标运行受控 HTTP 健康检查，并记录部署、回滚结果。

**架构：** 服务端在现有 `deployment` 模块内新增健康检查和运维操作两个运行态服务，`WorkbenchService` 继续作为聚合层输出部署运行摘要，`WorkbenchController` 暴露健康检查和运维操作 REST 接口。桌面端扩展 API 类型、运维面板和右侧部署状态卡片，所有动作完成后刷新工作台。

**技术栈：** Java 21、Spring Boot 3.5.15、JUnit 5、MockMvc、Java 标准 `HttpClient`、React 19.2.7、TypeScript 6.0.3、Vitest 4.1.9、本地 Maven `/Users/Masons/Ai/Maven` 和仓库 `/Users/Masons/Ai/Maven_Ai_Store`。

---

## 范围检查

本计划只实现 HTTP 健康检查、部署记录和回滚记录。第六阶段不执行 SSH，不运行远程部署脚本，不读取凭证，不实现定时探测、告警、多人审批、登录鉴权、数据库持久化和长任务队列。

健康检查地址只能来自已配置的 `DeploymentTarget.healthCheckUrl`。健康检查接口不接收临时 URL，不携带 Cookie、Token 或自定义 Header，不保存响应体。

## 文件结构

```text
server/src/main/java/com/matrixcode/deployment/
├── application/
│   ├── DeploymentHealthClient.java
│   ├── DeploymentHealthProbe.java
│   ├── DeploymentHealthService.java
│   ├── DeploymentOperationService.java
│   ├── DeploymentTargetService.java
│   └── HttpDeploymentHealthClient.java
└── domain/
    ├── DeploymentHealthCheck.java
    ├── DeploymentHealthStatus.java
    ├── DeploymentOperationRecord.java
    ├── DeploymentOperationStatus.java
    ├── DeploymentOperationType.java
    ├── DeploymentTarget.java
    └── DeploymentTargetStatus.java

server/src/main/java/com/matrixcode/workbench/
├── api/
│   └── WorkbenchController.java
├── application/
│   └── WorkbenchService.java
└── domain/
    ├── DeploymentRuntimeSummary.java
    └── ProjectWorkbench.java

server/src/test/java/com/matrixcode/deployment/
├── DeploymentHealthServiceTest.java
├── DeploymentOperationServiceTest.java
└── HttpDeploymentHealthClientTest.java

server/src/test/java/com/matrixcode/workbench/
├── WorkbenchControllerTest.java
└── WorkbenchServiceTest.java

desktop/src/
├── api/
│   ├── client.ts
│   └── client.test.ts
├── components/
│   ├── InspectorPanel.tsx
│   └── OpsPanel.tsx
├── test/
│   └── App.test.tsx
├── App.tsx
└── App.css

docs/development/local-run.md
docs/superpowers/plans/2026-06-24-matrixcode-deployment-health-operations.md
```

---

### 任务 1：新增部署运行态领域模型和目标查询能力

**文件：**

- 修改：`server/src/main/java/com/matrixcode/deployment/application/DeploymentTargetService.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/DeploymentHealthStatus.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/DeploymentHealthCheck.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/DeploymentOperationType.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/DeploymentOperationStatus.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/DeploymentOperationRecord.java`
- 修改：`server/src/test/java/com/matrixcode/deployment/DeploymentTargetServiceTest.java`

- [x] **步骤 1：编写目标查询失败测试**

在 `DeploymentTargetServiceTest` 增加测试：

```java
@Test
void 可以按项目和目标编号查询部署目标() {
    var target = service.configure("project-1", "测试环境", "https://test.example.com",
            "deploy@example.com", "部署", "https://test.example.com/health", "回滚");

    var found = service.requireByProject("project-1", target.id());

    assertThat(found).isEqualTo(target);
}

@Test
void 查询其他项目的部署目标会被拒绝() {
    var target = service.configure("project-1", "测试环境", "https://test.example.com",
            "deploy@example.com", "部署", "https://test.example.com/health", "回滚");

    assertThatThrownBy(() -> service.requireByProject("project-2", target.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("部署目标不存在");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DeploymentTargetServiceTest test
```

预期：编译失败，错误包含 `requireByProject` 不存在。

- [x] **步骤 3：实现目标查询方法**

在 `DeploymentTargetService` 中新增方法：

```java
public DeploymentTarget requireByProject(String projectId, String targetId) {
    requireText(projectId, "项目编号不能为空");
    requireText(targetId, "部署目标编号不能为空");
    synchronized (lock) {
        var target = targets.get(targetId);
        if (target == null || !projectId.equals(target.projectId())) {
            throw new IllegalArgumentException("部署目标不存在：" + targetId);
        }
        return target;
    }
}
```

- [x] **步骤 4：新增领域类型**

创建 `DeploymentHealthStatus.java`：

```java
package com.matrixcode.deployment.domain;

public enum DeploymentHealthStatus {
    HEALTHY,
    UNHEALTHY,
    UNREACHABLE
}
```

创建 `DeploymentHealthCheck.java`：

```java
package com.matrixcode.deployment.domain;

import java.time.Instant;

public record DeploymentHealthCheck(
        String id,
        String projectId,
        String targetId,
        String actorId,
        DeploymentHealthStatus status,
        Integer httpStatus,
        long durationMillis,
        String summary,
        Instant checkedAt
) {
    public DeploymentHealthCheck {
        actorId = actorId == null ? "" : actorId.trim();
        summary = summary == null ? "" : summary.trim();
    }
}
```

创建 `DeploymentOperationType.java`：

```java
package com.matrixcode.deployment.domain;

public enum DeploymentOperationType {
    DEPLOYMENT,
    ROLLBACK
}
```

创建 `DeploymentOperationStatus.java`：

```java
package com.matrixcode.deployment.domain;

public enum DeploymentOperationStatus {
    RECORDED,
    SUCCEEDED,
    FAILED
}
```

创建 `DeploymentOperationRecord.java`：

```java
package com.matrixcode.deployment.domain;

import java.time.Instant;

public record DeploymentOperationRecord(
        String id,
        String projectId,
        String targetId,
        String actorId,
        DeploymentOperationType type,
        DeploymentOperationStatus status,
        String note,
        Instant createdAt
) {
    public DeploymentOperationRecord {
        actorId = actorId == null ? "" : actorId.trim();
        note = note == null ? "" : note.trim();
    }
}
```

- [x] **步骤 5：运行目标服务测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DeploymentTargetServiceTest test
```

预期：`DeploymentTargetServiceTest` 通过。

- [x] **步骤 6：提交**

```bash
git add server/src/main/java/com/matrixcode/deployment/application/DeploymentTargetService.java
git add server/src/main/java/com/matrixcode/deployment/domain/DeploymentHealthStatus.java
git add server/src/main/java/com/matrixcode/deployment/domain/DeploymentHealthCheck.java
git add server/src/main/java/com/matrixcode/deployment/domain/DeploymentOperationType.java
git add server/src/main/java/com/matrixcode/deployment/domain/DeploymentOperationStatus.java
git add server/src/main/java/com/matrixcode/deployment/domain/DeploymentOperationRecord.java
git add server/src/test/java/com/matrixcode/deployment/DeploymentTargetServiceTest.java
git commit -m "feat: 添加部署运行态领域模型"
```

---

### 任务 2：实现受控 HTTP 健康检查服务

**文件：**

- 创建：`server/src/main/java/com/matrixcode/deployment/application/DeploymentHealthProbe.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/application/DeploymentHealthClient.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/application/HttpDeploymentHealthClient.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/application/DeploymentHealthService.java`
- 创建：`server/src/test/java/com/matrixcode/deployment/DeploymentHealthServiceTest.java`
- 创建：`server/src/test/java/com/matrixcode/deployment/HttpDeploymentHealthClientTest.java`

- [x] **步骤 1：编写健康检查服务失败测试**

创建 `DeploymentHealthServiceTest.java`：

```java
package com.matrixcode.deployment;

import com.matrixcode.deployment.application.DeploymentHealthProbe;
import com.matrixcode.deployment.application.DeploymentHealthService;
import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentHealthServiceTest {

    private final DeploymentTargetService targets = new DeploymentTargetService();

    @Test
    void 健康检查会使用部署目标保存的健康检查地址并记录结果() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");
        var service = new DeploymentHealthService(targets, uri -> {
            assertThat(uri.toString()).isEqualTo("https://pre.example.com/health");
            return new DeploymentHealthProbe(DeploymentHealthStatus.HEALTHY, 204, 18, "HTTP 204");
        });

        var check = service.check("demo", target.id(), "user-ops");

        assertThat(check.projectId()).isEqualTo("demo");
        assertThat(check.targetId()).isEqualTo(target.id());
        assertThat(check.actorId()).isEqualTo("user-ops");
        assertThat(check.status()).isEqualTo(DeploymentHealthStatus.HEALTHY);
        assertThat(check.httpStatus()).isEqualTo(204);
        assertThat(check.durationMillis()).isEqualTo(18);
        assertThat(check.summary()).isEqualTo("HTTP 204");
        assertThat(check.checkedAt()).isNotNull();
        assertThat(service.latestByProject("demo")).containsExactly(check);
    }

    @Test
    void 非Http协议会记录为不可达且不会调用客户端() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "ssh://pre.example.com/health", "回滚");
        var service = new DeploymentHealthService(targets, uri -> {
            throw new AssertionError("不应调用 HTTP 客户端");
        });

        var check = service.check("demo", target.id(), "user-ops");

        assertThat(check.status()).isEqualTo(DeploymentHealthStatus.UNREACHABLE);
        assertThat(check.httpStatus()).isNull();
        assertThat(check.summary()).contains("健康检查地址协议不支持");
    }

    @Test
    void 空操作者会被拒绝() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");
        var service = new DeploymentHealthService(targets,
                uri -> new DeploymentHealthProbe(DeploymentHealthStatus.HEALTHY, 200, 3, "HTTP 200"));

        assertThatThrownBy(() -> service.check("demo", target.id(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("操作者不能为空");
    }
}
```

- [x] **步骤 2：运行健康检查服务测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DeploymentHealthServiceTest test
```

预期：编译失败，错误包含 `DeploymentHealthService` 或 `DeploymentHealthProbe` 不存在。

- [x] **步骤 3：实现健康检查客户端接口和服务**

创建 `DeploymentHealthProbe.java`：

```java
package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.DeploymentHealthStatus;

public record DeploymentHealthProbe(
        DeploymentHealthStatus status,
        Integer httpStatus,
        long durationMillis,
        String summary
) {
}
```

创建 `DeploymentHealthClient.java`：

```java
package com.matrixcode.deployment.application;

import java.net.URI;

@FunctionalInterface
public interface DeploymentHealthClient {
    DeploymentHealthProbe check(URI uri);
}
```

创建 `DeploymentHealthService.java`：

```java
package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DeploymentHealthService {

    private static final int HISTORY_LIMIT = 20;

    private final Object lock = new Object();
    private final DeploymentTargetService targets;
    private final DeploymentHealthClient client;
    private final Map<String, ArrayDeque<DeploymentHealthCheck>> checks = new ConcurrentHashMap<>();

    public DeploymentHealthService(DeploymentTargetService targets, DeploymentHealthClient client) {
        this.targets = targets;
        this.client = client;
    }

    public DeploymentHealthCheck check(String projectId, String targetId, String actorId) {
        actorId = requireText(actorId, "操作者不能为空");
        var target = targets.requireByProject(projectId, targetId);
        var probe = probe(target.healthCheckUrl());
        var check = new DeploymentHealthCheck(
                UUID.randomUUID().toString(),
                projectId,
                target.id(),
                actorId,
                probe.status(),
                probe.httpStatus(),
                probe.durationMillis(),
                probe.summary(),
                Instant.now()
        );
        return remember(check);
    }

    public List<DeploymentHealthCheck> latestByProject(String projectId) {
        requireText(projectId, "项目编号不能为空");
        synchronized (lock) {
            return checks.getOrDefault(projectId, new ArrayDeque<>()).stream()
                    .sorted(Comparator.comparing(DeploymentHealthCheck::checkedAt).reversed())
                    .toList();
        }
    }

    public DeploymentHealthCheck latestForTarget(String projectId, String targetId) {
        requireText(projectId, "项目编号不能为空");
        requireText(targetId, "部署目标编号不能为空");
        synchronized (lock) {
            return checks.getOrDefault(projectId, new ArrayDeque<>()).stream()
                    .filter(check -> targetId.equals(check.targetId()))
                    .max(Comparator.comparing(DeploymentHealthCheck::checkedAt))
                    .orElse(null);
        }
    }

    private DeploymentHealthProbe probe(String healthCheckUrl) {
        try {
            var uri = URI.create(requireText(healthCheckUrl, "健康检查地址不能为空"));
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!List.of("http", "https").contains(scheme)) {
                return new DeploymentHealthProbe(DeploymentHealthStatus.UNREACHABLE, null, 0, "健康检查地址协议不支持");
            }
            return client.check(uri);
        } catch (RuntimeException exception) {
            return new DeploymentHealthProbe(DeploymentHealthStatus.UNREACHABLE, null, 0, "健康检查地址不可达");
        }
    }

    private DeploymentHealthCheck remember(DeploymentHealthCheck check) {
        synchronized (lock) {
            var records = checks.computeIfAbsent(check.projectId(), ignored -> new ArrayDeque<>());
            records.addFirst(check);
            while (records.size() > HISTORY_LIMIT) {
                records.removeLast();
            }
            return check;
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
```

- [x] **步骤 4：运行健康检查服务测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DeploymentHealthServiceTest test
```

预期：`DeploymentHealthServiceTest` 通过。

- [x] **步骤 5：编写 HTTP 客户端失败测试**

创建 `HttpDeploymentHealthClientTest.java`：

```java
package com.matrixcode.deployment;

import com.matrixcode.deployment.application.HttpDeploymentHealthClient;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HttpDeploymentHealthClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 二三开头状态码视为健康() throws Exception {
        var uri = startServer(204);
        var client = new HttpDeploymentHealthClient();

        var probe = client.check(uri);

        assertThat(probe.status()).isEqualTo(DeploymentHealthStatus.HEALTHY);
        assertThat(probe.httpStatus()).isEqualTo(204);
        assertThat(probe.summary()).isEqualTo("HTTP 204");
        assertThat(probe.durationMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 四五开头状态码视为非健康() throws Exception {
        var uri = startServer(503);
        var client = new HttpDeploymentHealthClient();

        var probe = client.check(uri);

        assertThat(probe.status()).isEqualTo(DeploymentHealthStatus.UNHEALTHY);
        assertThat(probe.httpStatus()).isEqualTo(503);
        assertThat(probe.summary()).isEqualTo("HTTP 503");
    }

    @Test
    void 连接失败视为不可达() {
        var client = new HttpDeploymentHealthClient();

        var probe = client.check(URI.create("http://127.0.0.1:1/health"));

        assertThat(probe.status()).isEqualTo(DeploymentHealthStatus.UNREACHABLE);
        assertThat(probe.httpStatus()).isNull();
        assertThat(probe.summary()).isEqualTo("健康检查地址不可达");
    }

    private URI startServer(int statusCode) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            var body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:%d/health".formatted(server.getAddress().getPort()));
    }
}
```

- [x] **步骤 6：运行 HTTP 客户端测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=HttpDeploymentHealthClientTest test
```

预期：编译失败，错误包含 `HttpDeploymentHealthClient` 不存在。

- [x] **步骤 7：实现 HTTP 客户端**

创建 `HttpDeploymentHealthClient.java`：

```java
package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class HttpDeploymentHealthClient implements DeploymentHealthClient {

    private final HttpClient client;

    public HttpDeploymentHealthClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    HttpDeploymentHealthClient(HttpClient client) {
        this.client = client;
    }

    @Override
    public DeploymentHealthProbe check(URI uri) {
        var start = System.nanoTime();
        try {
            var request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            var durationMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            var statusCode = response.statusCode();
            var status = statusCode >= 200 && statusCode < 400
                    ? DeploymentHealthStatus.HEALTHY
                    : DeploymentHealthStatus.UNHEALTHY;
            return new DeploymentHealthProbe(status, statusCode, durationMillis, "HTTP " + statusCode);
        } catch (Exception exception) {
            var durationMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            return new DeploymentHealthProbe(DeploymentHealthStatus.UNREACHABLE, null, durationMillis, "健康检查地址不可达");
        }
    }
}
```

- [x] **步骤 8：运行健康检查相关测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DeploymentHealthServiceTest,HttpDeploymentHealthClientTest test
```

预期：两个测试类通过。

- [x] **步骤 9：提交**

```bash
git add server/src/main/java/com/matrixcode/deployment/application/DeploymentHealthProbe.java
git add server/src/main/java/com/matrixcode/deployment/application/DeploymentHealthClient.java
git add server/src/main/java/com/matrixcode/deployment/application/HttpDeploymentHealthClient.java
git add server/src/main/java/com/matrixcode/deployment/application/DeploymentHealthService.java
git add server/src/test/java/com/matrixcode/deployment/DeploymentHealthServiceTest.java
git add server/src/test/java/com/matrixcode/deployment/HttpDeploymentHealthClientTest.java
git commit -m "feat: 添加部署健康检查服务"
```

---

### 任务 3：实现部署和回滚操作记录服务

**文件：**

- 创建：`server/src/main/java/com/matrixcode/deployment/application/DeploymentOperationService.java`
- 创建：`server/src/test/java/com/matrixcode/deployment/DeploymentOperationServiceTest.java`

- [x] **步骤 1：编写运维操作记录失败测试**

创建 `DeploymentOperationServiceTest.java`：

```java
package com.matrixcode.deployment;

import com.matrixcode.deployment.application.DeploymentOperationService;
import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentOperationServiceTest {

    private final DeploymentTargetService targets = new DeploymentTargetService();
    private final DeploymentOperationService service = new DeploymentOperationService(targets);

    @Test
    void 可以记录部署和回滚操作并按目标查询最新记录() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");

        var deployment = service.record("demo", target.id(), "user-ops",
                DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, "按发布单完成预发部署");
        var rollback = service.record("demo", target.id(), "user-ops",
                DeploymentOperationType.ROLLBACK, DeploymentOperationStatus.RECORDED, "记录回滚方案");

        assertThat(deployment.type()).isEqualTo(DeploymentOperationType.DEPLOYMENT);
        assertThat(deployment.status()).isEqualTo(DeploymentOperationStatus.SUCCEEDED);
        assertThat(deployment.note()).isEqualTo("按发布单完成预发部署");
        assertThat(rollback.type()).isEqualTo(DeploymentOperationType.ROLLBACK);
        assertThat(service.latestDeploymentForTarget("demo", target.id())).isEqualTo(deployment);
        assertThat(service.latestRollbackForTarget("demo", target.id())).isEqualTo(rollback);
        assertThat(service.latestByProject("demo")).containsExactly(rollback, deployment);
    }

    @Test
    void 说明为空会被拒绝() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");

        assertThatThrownBy(() -> service.record("demo", target.id(), "user-ops",
                DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("操作说明不能为空");
    }

    @Test
    void 其他项目不能记录该目标操作() {
        var target = targets.configure("demo", "预发环境", "https://pre.example.com",
                "deploy@example.com", "部署", "https://pre.example.com/health", "回滚");

        assertThatThrownBy(() -> service.record("other", target.id(), "user-ops",
                DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, "部署完成"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("部署目标不存在");
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DeploymentOperationServiceTest test
```

预期：编译失败，错误包含 `DeploymentOperationService` 不存在。

- [x] **步骤 3：实现运维操作记录服务**

创建 `DeploymentOperationService.java`：

```java
package com.matrixcode.deployment.application;

import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DeploymentOperationService {

    private static final int HISTORY_LIMIT = 20;

    private final Object lock = new Object();
    private final DeploymentTargetService targets;
    private final Map<String, ArrayDeque<DeploymentOperationRecord>> operations = new ConcurrentHashMap<>();

    public DeploymentOperationService(DeploymentTargetService targets) {
        this.targets = targets;
    }

    public DeploymentOperationRecord record(
            String projectId,
            String targetId,
            String actorId,
            DeploymentOperationType type,
            DeploymentOperationStatus status,
            String note
    ) {
        actorId = requireText(actorId, "操作者不能为空");
        note = requireText(note, "操作说明不能为空");
        if (type == null) {
            throw new IllegalArgumentException("运维操作类型不支持");
        }
        if (status == null) {
            throw new IllegalArgumentException("运维操作状态不支持");
        }
        var target = targets.requireByProject(projectId, targetId);
        var record = new DeploymentOperationRecord(
                UUID.randomUUID().toString(),
                projectId,
                target.id(),
                actorId,
                type,
                status,
                note,
                Instant.now()
        );
        return remember(record);
    }

    public List<DeploymentOperationRecord> latestByProject(String projectId) {
        requireText(projectId, "项目编号不能为空");
        synchronized (lock) {
            return operations.getOrDefault(projectId, new ArrayDeque<>()).stream()
                    .sorted(Comparator.comparing(DeploymentOperationRecord::createdAt).reversed())
                    .toList();
        }
    }

    public DeploymentOperationRecord latestDeploymentForTarget(String projectId, String targetId) {
        return latestForTarget(projectId, targetId, DeploymentOperationType.DEPLOYMENT);
    }

    public DeploymentOperationRecord latestRollbackForTarget(String projectId, String targetId) {
        return latestForTarget(projectId, targetId, DeploymentOperationType.ROLLBACK);
    }

    private DeploymentOperationRecord latestForTarget(String projectId, String targetId, DeploymentOperationType type) {
        requireText(projectId, "项目编号不能为空");
        requireText(targetId, "部署目标编号不能为空");
        synchronized (lock) {
            return operations.getOrDefault(projectId, new ArrayDeque<>()).stream()
                    .filter(record -> targetId.equals(record.targetId()))
                    .filter(record -> type == record.type())
                    .max(Comparator.comparing(DeploymentOperationRecord::createdAt))
                    .orElse(null);
        }
    }

    private DeploymentOperationRecord remember(DeploymentOperationRecord record) {
        synchronized (lock) {
            var records = operations.computeIfAbsent(record.projectId(), ignored -> new ArrayDeque<>());
            records.addFirst(record);
            while (records.size() > HISTORY_LIMIT) {
                records.removeLast();
            }
            return record;
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
```

- [x] **步骤 4：运行运维操作记录测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DeploymentOperationServiceTest test
```

预期：`DeploymentOperationServiceTest` 通过。

- [x] **步骤 5：提交**

```bash
git add server/src/main/java/com/matrixcode/deployment/application/DeploymentOperationService.java
git add server/src/test/java/com/matrixcode/deployment/DeploymentOperationServiceTest.java
git commit -m "feat: 添加部署和回滚操作记录"
```

---

### 任务 4：接入工作台摘要和 REST 接口

**文件：**

- 创建：`server/src/main/java/com/matrixcode/workbench/domain/DeploymentRuntimeSummary.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/domain/ProjectWorkbench.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`
- 修改：`server/src/main/java/com/matrixcode/workbench/api/WorkbenchController.java`
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java`
- 修改：`server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java`
- 修改：`server/src/test/java/com/matrixcode/localexecution/WorkbenchLocalExecutionTest.java`

- [x] **步骤 1：编写工作台摘要失败测试**

修改 `WorkbenchServiceTest`：

1. 新增 import：

```java
import com.matrixcode.deployment.application.DeploymentHealthProbe;
import com.matrixcode.deployment.application.DeploymentHealthService;
import com.matrixcode.deployment.application.DeploymentOperationService;
import com.matrixcode.deployment.domain.DeploymentHealthStatus;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
```

2. 把 `DeploymentTargetService` 提取成字段，并为 `WorkbenchService` 构造器传入运行态服务：

```java
private final DeploymentTargetService deploymentTargetService = new DeploymentTargetService();
private final DeploymentHealthService deploymentHealthService = new DeploymentHealthService(
        deploymentTargetService,
        uri -> new DeploymentHealthProbe(DeploymentHealthStatus.HEALTHY, 200, 12, "HTTP 200")
);
private final DeploymentOperationService deploymentOperationService = new DeploymentOperationService(deploymentTargetService);
```

3. 将 `new DeploymentTargetService()` 替换为 `deploymentTargetService`，并在构造器末尾追加 `deploymentHealthService` 和 `deploymentOperationService`。

4. 新增测试：

```java
@Test
void 部署健康检查和运维操作会进入工作台运行摘要() {
    var target = service.configureDeploymentTarget("demo", "预发环境", "https://pre.example.com",
            "deploy@example.com", "按发布单部署", "https://pre.example.com/health", "回滚上一版本");
    var health = service.runDeploymentHealthCheck("demo", target.id(), "user-ops");
    var deployment = service.recordDeploymentOperation("demo", target.id(), "user-ops",
            DeploymentOperationType.DEPLOYMENT, DeploymentOperationStatus.SUCCEEDED, "预发部署完成");
    var rollback = service.recordDeploymentOperation("demo", target.id(), "user-ops",
            DeploymentOperationType.ROLLBACK, DeploymentOperationStatus.RECORDED, "记录回滚方案");

    var workbench = service.get("demo");

    assertThat(health.status()).isEqualTo(DeploymentHealthStatus.HEALTHY);
    assertThat(workbench.deploymentRuntimeSummaries()).hasSize(1);
    assertThat(workbench.deploymentRuntimeSummaries().getFirst().targetId()).isEqualTo(target.id());
    assertThat(workbench.deploymentRuntimeSummaries().getFirst().latestHealthCheck()).isEqualTo(health);
    assertThat(workbench.deploymentRuntimeSummaries().getFirst().latestDeploymentOperation()).isEqualTo(deployment);
    assertThat(workbench.deploymentRuntimeSummaries().getFirst().latestRollbackOperation()).isEqualTo(rollback);
    assertThat(workbench.events()).extracting("type")
            .contains("DEPLOYMENT_HEALTH_CHECKED", "DEPLOYMENT_OPERATION_RECORDED");
}
```

- [x] **步骤 2：运行工作台服务测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchServiceTest test
```

预期：编译失败，错误包含 `deploymentRuntimeSummaries`、`runDeploymentHealthCheck` 或 `recordDeploymentOperation` 不存在。

- [x] **步骤 3：实现工作台运行摘要**

创建 `DeploymentRuntimeSummary.java`：

```java
package com.matrixcode.workbench.domain;

import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;

public record DeploymentRuntimeSummary(
        String targetId,
        DeploymentHealthCheck latestHealthCheck,
        DeploymentOperationRecord latestDeploymentOperation,
        DeploymentOperationRecord latestRollbackOperation
) {
}
```

修改 `ProjectWorkbench`：

1. 新增字段，放在 `deploymentTargets` 后：

```java
List<DeploymentRuntimeSummary> deploymentRuntimeSummaries,
```

2. 紧凑构造器中增加：

```java
deploymentRuntimeSummaries = List.copyOf(deploymentRuntimeSummaries);
```

修改 `WorkbenchService`：

1. 新增 import：

```java
import com.matrixcode.deployment.application.DeploymentHealthService;
import com.matrixcode.deployment.application.DeploymentOperationService;
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
import com.matrixcode.workbench.domain.DeploymentRuntimeSummary;
```

2. 新增字段和构造参数：

```java
private final DeploymentHealthService deploymentHealthService;
private final DeploymentOperationService deploymentOperationService;
```

构造器末尾追加：

```java
DeploymentHealthService deploymentHealthService,
DeploymentOperationService deploymentOperationService
```

赋值：

```java
this.deploymentHealthService = deploymentHealthService;
this.deploymentOperationService = deploymentOperationService;
```

3. 在 `get` 中构造运行摘要：

```java
var deploymentRuntimeSummaries = deploymentRuntimeSummaries(projectId, deploymentTargets);
```

传给 `ProjectWorkbench`，放在 `deploymentTargets` 后。

4. 新增公开方法：

```java
public DeploymentHealthCheck runDeploymentHealthCheck(String projectId, String targetId, String actorId) {
    var check = deploymentHealthService.check(projectId, targetId, actorId);
    publish(projectId, "DEPLOYMENT_HEALTH_CHECKED", "运维运行了部署健康检查");
    return check;
}

public DeploymentOperationRecord recordDeploymentOperation(
        String projectId,
        String targetId,
        String actorId,
        DeploymentOperationType type,
        DeploymentOperationStatus status,
        String note
) {
    var record = deploymentOperationService.record(projectId, targetId, actorId, type, status, note);
    publish(projectId, "DEPLOYMENT_OPERATION_RECORDED", operationMessage(type));
    return record;
}
```

5. 新增私有方法：

```java
private List<DeploymentRuntimeSummary> deploymentRuntimeSummaries(String projectId, List<DeploymentTarget> targets) {
    return targets.stream()
            .map(target -> new DeploymentRuntimeSummary(
                    target.id(),
                    deploymentHealthService.latestForTarget(projectId, target.id()),
                    deploymentOperationService.latestDeploymentForTarget(projectId, target.id()),
                    deploymentOperationService.latestRollbackForTarget(projectId, target.id())
            ))
            .toList();
}

private String operationMessage(DeploymentOperationType type) {
    return type == DeploymentOperationType.ROLLBACK ? "运维记录了回滚结果" : "运维记录了部署结果";
}
```

- [x] **步骤 4：更新构造器调用点**

更新 `WorkbenchServiceTest`、`WorkbenchLocalExecutionTest` 等直接 new `WorkbenchService` 的测试。每个测试都应创建共享的 `DeploymentTargetService`：

```java
var deploymentTargetService = new DeploymentTargetService();
var deploymentHealthService = new DeploymentHealthService(
        deploymentTargetService,
        uri -> new DeploymentHealthProbe(DeploymentHealthStatus.HEALTHY, 200, 5, "HTTP 200")
);
var deploymentOperationService = new DeploymentOperationService(deploymentTargetService);
```

然后传入 `WorkbenchService` 构造器。

- [x] **步骤 5：运行工作台服务测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchServiceTest,WorkbenchLocalExecutionTest test
```

预期：两个测试类通过。

- [x] **步骤 6：编写 REST 接口失败测试**

修改 `WorkbenchControllerTest`，新增测试：

```java
@Test
void 可以通过接口运行健康检查并记录部署和回滚操作() throws Exception {
    var targetResponse = mockMvc.perform(post("/api/projects/demo/deployments/targets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"environmentName":"测试环境","environmentUrl":"https://test.example.com","sshAddress":"deploy@example.com","deployNote":"按部署文档执行","healthCheckUrl":"http://127.0.0.1:1/health","rollbackNote":"回滚上一版本"}
                            """))
            .andExpect(status().isOk())
            .andReturn();
    var targetId = JsonPath.read(targetResponse.getResponse().getContentAsString(), "$.id").toString();

    mockMvc.perform(post("/api/projects/demo/deployments/targets/%s/health-checks".formatted(targetId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actorId\":\"user-ops\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.targetId").value(targetId))
            .andExpect(jsonPath("$.status").value("UNREACHABLE"))
            .andExpect(jsonPath("$.actorId").value("user-ops"));

    mockMvc.perform(post("/api/projects/demo/deployments/targets/%s/operations".formatted(targetId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"actorId":"user-ops","type":"DEPLOYMENT","status":"SUCCEEDED","note":"预发部署完成"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("DEPLOYMENT"))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"));

    mockMvc.perform(post("/api/projects/demo/deployments/targets/%s/operations".formatted(targetId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"actorId":"user-ops","type":"ROLLBACK","status":"RECORDED","note":"记录回滚方案"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("ROLLBACK"))
            .andExpect(jsonPath("$.status").value("RECORDED"));

    mockMvc.perform(get("/api/projects/demo/workbench"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deploymentRuntimeSummaries[0].targetId").value(targetId))
            .andExpect(jsonPath("$.deploymentRuntimeSummaries[0].latestHealthCheck.status").value("UNREACHABLE"))
            .andExpect(jsonPath("$.deploymentRuntimeSummaries[0].latestDeploymentOperation.note").value("预发部署完成"))
            .andExpect(jsonPath("$.deploymentRuntimeSummaries[0].latestRollbackOperation.note").value("记录回滚方案"));
}
```

- [x] **步骤 7：运行控制器测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchControllerTest test
```

预期：HTTP 404 或编译失败，错误指向健康检查/操作记录端点不存在。

- [x] **步骤 8：实现 REST 接口**

修改 `WorkbenchController`：

1. 新增 import：

```java
import com.matrixcode.deployment.domain.DeploymentHealthCheck;
import com.matrixcode.deployment.domain.DeploymentOperationRecord;
import com.matrixcode.deployment.domain.DeploymentOperationStatus;
import com.matrixcode.deployment.domain.DeploymentOperationType;
```

2. 在部署目标接口后新增：

```java
@PostMapping("/deployments/targets/{targetId}/health-checks")
public DeploymentHealthCheck runDeploymentHealthCheck(
        @PathVariable String projectId,
        @PathVariable String targetId,
        @RequestBody DeploymentHealthCheckCommand command
) {
    return service.runDeploymentHealthCheck(projectId, targetId, command.actorId());
}

@PostMapping("/deployments/targets/{targetId}/operations")
public DeploymentOperationRecord recordDeploymentOperation(
        @PathVariable String projectId,
        @PathVariable String targetId,
        @RequestBody DeploymentOperationCommand command
) {
    return service.recordDeploymentOperation(
            projectId,
            targetId,
            command.actorId(),
            command.type(),
            command.status(),
            command.note()
    );
}
```

3. 增加请求 record：

```java
public record DeploymentHealthCheckCommand(String actorId) {
}

public record DeploymentOperationCommand(
        String actorId,
        DeploymentOperationType type,
        DeploymentOperationStatus status,
        String note
) {
}
```

- [x] **步骤 9：运行工作台接口测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchControllerTest,WorkbenchServiceTest,WorkbenchLocalExecutionTest test
```

预期：三个测试类通过。

- [x] **步骤 10：提交**

```bash
git add server/src/main/java/com/matrixcode/workbench/domain/DeploymentRuntimeSummary.java
git add server/src/main/java/com/matrixcode/workbench/domain/ProjectWorkbench.java
git add server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java
git add server/src/main/java/com/matrixcode/workbench/api/WorkbenchController.java
git add server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java
git add server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java
git add server/src/test/java/com/matrixcode/localexecution/WorkbenchLocalExecutionTest.java
git commit -m "feat: 接入部署运行态工作台接口"
```

---

### 任务 5：扩展桌面 API、运维面板和部署状态卡片

**文件：**

- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`
- 修改：`desktop/src/components/OpsPanel.tsx`
- 修改：`desktop/src/components/InspectorPanel.tsx`
- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/App.css`
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写桌面 API 失败测试**

修改 `desktop/src/api/client.test.ts`：

1. import 增加：

```ts
  recordDeploymentOperation,
  runDeploymentHealthCheck,
```

2. 在“角色工作台 API 客户端”测试组中增加：

```ts
it('运行部署健康检查时调用目标健康检查地址', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ id: 'health-1', status: 'HEALTHY' })
  });
  vi.stubGlobal('fetch', fetchMock);
  const input = { actorId: 'user-ops' };

  await runDeploymentHealthCheck('demo', 'target/1', input, 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith(
    'http://localhost:8080/api/projects/demo/deployments/targets/target%2F1/health-checks',
    {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify(input)
    }
  );
});

it('记录部署运行操作时调用目标操作地址', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ id: 'operation-1', type: 'DEPLOYMENT', status: 'SUCCEEDED' })
  });
  vi.stubGlobal('fetch', fetchMock);
  const input = {
    actorId: 'user-ops',
    type: 'DEPLOYMENT' as const,
    status: 'SUCCEEDED' as const,
    note: '预发部署完成'
  };

  await recordDeploymentOperation('demo', 'target/1', input, 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith(
    'http://localhost:8080/api/projects/demo/deployments/targets/target%2F1/operations',
    {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify(input)
    }
  );
});
```

- [x] **步骤 2：运行桌面 API 测试验证失败**

运行：

```bash
cd desktop
npm test -- src/api/client.test.ts
```

预期：Vitest 失败，错误包含 `runDeploymentHealthCheck` 或 `recordDeploymentOperation` 未导出。

- [x] **步骤 3：扩展桌面 API 类型和函数**

修改 `client.ts`：

1. 在部署目标类型后新增：

```ts
export type DeploymentHealthStatus = 'HEALTHY' | 'UNHEALTHY' | 'UNREACHABLE';
export type DeploymentHealthCheck = {
  id: string;
  projectId: string;
  targetId: string;
  actorId: string;
  status: DeploymentHealthStatus;
  httpStatus: number | null;
  durationMillis: number;
  summary: string;
  checkedAt: string;
};
export type DeploymentOperationType = 'DEPLOYMENT' | 'ROLLBACK';
export type DeploymentOperationStatus = 'RECORDED' | 'SUCCEEDED' | 'FAILED';
export type DeploymentOperationRecord = {
  id: string;
  projectId: string;
  targetId: string;
  actorId: string;
  type: DeploymentOperationType;
  status: DeploymentOperationStatus;
  note: string;
  createdAt: string;
};
export type DeploymentRuntimeSummary = {
  targetId: string;
  latestHealthCheck: DeploymentHealthCheck | null;
  latestDeploymentOperation: DeploymentOperationRecord | null;
  latestRollbackOperation: DeploymentOperationRecord | null;
};
export type DeploymentOperationInput = {
  actorId: string;
  type: DeploymentOperationType;
  status: DeploymentOperationStatus;
  note: string;
};
```

2. `ProjectWorkbench` 增加字段：

```ts
deploymentRuntimeSummaries: DeploymentRuntimeSummary[];
```

3. 在 `configureDeploymentTarget` 后新增：

```ts
export function runDeploymentHealthCheck(
  projectId: string,
  targetId: string,
  input: { actorId: string },
  serverUrl = matrixCodeServerUrl()
): Promise<DeploymentHealthCheck> {
  return requestJson<DeploymentHealthCheck>(
    projectUrl(serverUrl, projectId, `/deployments/targets/${encodeURIComponent(targetId)}/health-checks`),
    {
      method: 'POST',
      body: JSON.stringify(input)
    }
  );
}

export function recordDeploymentOperation(
  projectId: string,
  targetId: string,
  input: DeploymentOperationInput,
  serverUrl = matrixCodeServerUrl()
): Promise<DeploymentOperationRecord> {
  return requestJson<DeploymentOperationRecord>(
    projectUrl(serverUrl, projectId, `/deployments/targets/${encodeURIComponent(targetId)}/operations`),
    {
      method: 'POST',
      body: JSON.stringify(input)
    }
  );
}
```

- [x] **步骤 4：运行桌面 API 测试验证通过**

运行：

```bash
cd desktop
npm test -- src/api/client.test.ts
```

预期：`client.test.ts` 通过。

- [x] **步骤 5：编写桌面 UI 失败测试**

修改 `desktop/src/test/App.test.tsx`：

1. import 增加：

```ts
  recordDeploymentOperation,
  runDeploymentHealthCheck,
```

2. `vi.mock('../api/client', ...)` 增加：

```ts
  runDeploymentHealthCheck: vi.fn(),
  recordDeploymentOperation: vi.fn()
```

3. mock 变量区增加：

```ts
const 运行健康检查 = vi.mocked(runDeploymentHealthCheck);
const 记录部署操作 = vi.mocked(recordDeploymentOperation);
```

4. `基础工作台` 增加字段：

```ts
deploymentRuntimeSummaries: [],
```

放在 `deploymentTargets` 后。

5. `beforeEach` 增加默认返回：

```ts
运行健康检查.mockResolvedValue({
  id: 'health-1',
  projectId: 'demo',
  targetId: 'target-1',
  actorId: 'user-ops',
  status: 'HEALTHY',
  httpStatus: 200,
  durationMillis: 18,
  summary: 'HTTP 200',
  checkedAt: '2026-06-24T12:00:00Z'
});
记录部署操作.mockResolvedValue({
  id: 'operation-1',
  projectId: 'demo',
  targetId: 'target-1',
  actorId: 'user-ops',
  type: 'DEPLOYMENT',
  status: 'SUCCEEDED',
  note: '预发部署完成。',
  createdAt: '2026-06-24T12:05:00Z'
});
```

6. 新增带部署目标和运行摘要的 fixture：

```ts
const 运维运行态工作台: ProjectWorkbench = {
  ...测试通过后工作台,
  deploymentTargets: [
    {
      id: 'target-1',
      projectId: 'demo',
      environmentName: '预发环境',
      environmentUrl: 'https://pre.example.com',
      sshAddress: 'deploy@pre.example.com',
      deployNote: '按发布单部署。',
      healthCheckUrl: 'https://pre.example.com/health',
      rollbackNote: '回滚到上一稳定版本。',
      status: 'RECORDED',
      remoteExecuted: false,
      updatedAt: '2026-06-24T11:00:00Z'
    }
  ],
  deploymentRuntimeSummaries: [
    {
      targetId: 'target-1',
      latestHealthCheck: {
        id: 'health-1',
        projectId: 'demo',
        targetId: 'target-1',
        actorId: 'user-ops',
        status: 'HEALTHY',
        httpStatus: 200,
        durationMillis: 18,
        summary: 'HTTP 200',
        checkedAt: '2026-06-24T12:00:00Z'
      },
      latestDeploymentOperation: {
        id: 'operation-1',
        projectId: 'demo',
        targetId: 'target-1',
        actorId: 'user-ops',
        type: 'DEPLOYMENT',
        status: 'SUCCEEDED',
        note: '预发部署完成。',
        createdAt: '2026-06-24T12:05:00Z'
      },
      latestRollbackOperation: {
        id: 'operation-2',
        projectId: 'demo',
        targetId: 'target-1',
        actorId: 'user-ops',
        type: 'ROLLBACK',
        status: 'RECORDED',
        note: '记录回滚方案。',
        createdAt: '2026-06-24T12:10:00Z'
      }
    }
  ]
};
```

7. 新增测试：

```ts
it('运维可以运行健康检查并记录部署和回滚', async () => {
  加载项目工作台.mockResolvedValueOnce(运维运行态工作台).mockResolvedValue(运维运行态工作台);
  render(<App />);

  const 角色工作区 = within(await screen.findByRole('navigation', { name: '角色工作区' }));
  fireEvent.click(角色工作区.getByRole('button', { name: '运维' }));

  fireEvent.click(screen.getByRole('button', { name: '运行健康检查' }));

  await waitFor(() =>
    expect(运行健康检查).toHaveBeenCalledWith('demo', 'target-1', { actorId: 'user-ops' })
  );

  fireEvent.change(screen.getByLabelText('部署记录说明'), { target: { value: '预发部署完成。' } });
  fireEvent.change(screen.getByLabelText('部署结果'), { target: { value: 'SUCCEEDED' } });
  fireEvent.click(screen.getByRole('button', { name: '记录部署' }));

  await waitFor(() =>
    expect(记录部署操作).toHaveBeenCalledWith('demo', 'target-1', {
      actorId: 'user-ops',
      type: 'DEPLOYMENT',
      status: 'SUCCEEDED',
      note: '预发部署完成。'
    })
  );

  fireEvent.change(screen.getByLabelText('回滚记录说明'), { target: { value: '记录回滚方案。' } });
  fireEvent.change(screen.getByLabelText('回滚结果'), { target: { value: 'RECORDED' } });
  fireEvent.click(screen.getByRole('button', { name: '记录回滚' }));

  await waitFor(() =>
    expect(记录部署操作).toHaveBeenLastCalledWith('demo', 'target-1', {
      actorId: 'user-ops',
      type: 'ROLLBACK',
      status: 'RECORDED',
      note: '记录回滚方案。'
    })
  );
});

it('右侧部署状态展示最近健康检查和运维记录', async () => {
  加载项目工作台.mockResolvedValueOnce(运维运行态工作台);

  render(<App />);

  const 部署状态 = within(await screen.findByLabelText('部署状态'));
  expect(部署状态.getByText(/预发环境/)).toBeTruthy();
  expect(部署状态.getByText(/健康：健康 · HTTP 200 · 18 ms/)).toBeTruthy();
  expect(部署状态.getByText(/部署：成功 · 预发部署完成。/)).toBeTruthy();
  expect(部署状态.getByText(/回滚：已记录 · 记录回滚方案。/)).toBeTruthy();
});
```

- [x] **步骤 6：运行桌面 UI 测试验证失败**

运行：

```bash
cd desktop
npm test -- src/test/App.test.tsx
```

预期：Vitest 失败，错误包含函数未 mock、`deploymentRuntimeSummaries` 类型缺失、按钮不存在或右侧文本不存在。

- [x] **步骤 7：实现运维面板运行记录 UI**

修改 `OpsPanel.tsx`：

1. import 扩展：

```ts
import { useState, type FormEvent } from 'react';
import type {
  DeploymentOperationStatus,
  DeploymentOperationType,
  DeploymentRuntimeSummary,
  DeploymentTarget
} from '../api/client';
```

2. props 扩展：

```ts
export type OpsDeploymentOperationInput = {
  type: DeploymentOperationType;
  status: DeploymentOperationStatus;
  note: string;
};

type OpsPanelProps = {
  deploymentTargets: DeploymentTarget[];
  deploymentRuntimeSummaries: DeploymentRuntimeSummary[];
  onConfigureTarget: (input: OpsDeploymentTargetInput) => Promise<void>;
  onRunHealthCheck: (targetId: string) => Promise<void>;
  onRecordDeploymentOperation: (targetId: string, input: OpsDeploymentOperationInput) => Promise<void>;
};
```

3. 增加状态：

```ts
const [selectedTargetId, setSelectedTargetId] = useState('');
const [deploymentStatus, setDeploymentStatus] = useState<DeploymentOperationStatus>('SUCCEEDED');
const [deploymentNote, setDeploymentNote] = useState('');
const [rollbackStatus, setRollbackStatus] = useState<DeploymentOperationStatus>('RECORDED');
const [rollbackNote, setRollbackNote] = useState('');
const [runtimeSubmitting, setRuntimeSubmitting] = useState('');
const [runtimeErrorMessage, setRuntimeErrorMessage] = useState('');
```

4. 在组件内计算当前目标：

```ts
const activeTargetId = selectedTargetId || deploymentTargets[0]?.id || '';
const selectedRuntimeSummary = deploymentRuntimeSummaries.find((summary) => summary.targetId === activeTargetId);
```

5. 新增处理函数：

```ts
async function handleRunHealthCheck() {
  if (!activeTargetId) {
    setRuntimeErrorMessage('请先保存部署目标');
    return;
  }
  setRuntimeSubmitting('health');
  setRuntimeErrorMessage('');
  try {
    await onRunHealthCheck(activeTargetId);
  } catch (error) {
    const message = messageFromError(error, '运行健康检查失败，请稍后重试');
    setRuntimeErrorMessage(message === syncFailureMessage ? '' : message);
  } finally {
    setRuntimeSubmitting('');
  }
}

async function handleRecordOperation(type: DeploymentOperationType) {
  if (!activeTargetId) {
    setRuntimeErrorMessage('请先保存部署目标');
    return;
  }
  const note = type === 'ROLLBACK' ? rollbackNote.trim() : deploymentNote.trim();
  const status = type === 'ROLLBACK' ? rollbackStatus : deploymentStatus;
  if (!note) {
    setRuntimeErrorMessage(type === 'ROLLBACK' ? '请填写回滚记录说明' : '请填写部署记录说明');
    return;
  }
  setRuntimeSubmitting(type);
  setRuntimeErrorMessage('');
  try {
    await onRecordDeploymentOperation(activeTargetId, { type, status, note });
    if (type === 'ROLLBACK') {
      setRollbackNote('');
    } else {
      setDeploymentNote('');
    }
  } catch (error) {
    const message = messageFromError(error, '记录运维操作失败，请稍后重试');
    setRuntimeErrorMessage(message === syncFailureMessage ? '' : message);
  } finally {
    setRuntimeSubmitting('');
  }
}
```

6. 在配置表单后新增第二个区域：

```tsx
<section className="role-form" aria-label="部署运行记录">
  <h4 className="form-section-title">部署运行记录</h4>
  {deploymentTargets.length ? (
    <>
      <label className="field" htmlFor="ops-target-id">
        <span>部署目标</span>
        <select id="ops-target-id" onChange={(event) => setSelectedTargetId(event.target.value)} value={activeTargetId}>
          {deploymentTargets.map((deploymentTarget) => (
            <option key={deploymentTarget.id} value={deploymentTarget.id}>
              {deploymentTarget.environmentName}
            </option>
          ))}
        </select>
      </label>
      {selectedRuntimeSummary?.latestHealthCheck ? (
        <p className="form-hint">
          最近健康检查：{selectedRuntimeSummary.latestHealthCheck.status} · {selectedRuntimeSummary.latestHealthCheck.summary}
        </p>
      ) : (
        <p className="form-hint">暂无健康检查记录</p>
      )}
      <div className="form-actions">
        <button
          className="secondary-button"
          disabled={runtimeSubmitting === 'health'}
          onClick={() => void handleRunHealthCheck()}
          type="button"
        >
          {runtimeSubmitting === 'health' ? '检查中' : '运行健康检查'}
        </button>
      </div>
      <div className="field-grid">
        <label className="field" htmlFor="ops-deployment-status">
          <span>部署结果</span>
          <select
            id="ops-deployment-status"
            onChange={(event) => setDeploymentStatus(event.target.value as DeploymentOperationStatus)}
            value={deploymentStatus}
          >
            <option value="SUCCEEDED">成功</option>
            <option value="FAILED">失败</option>
            <option value="RECORDED">已记录</option>
          </select>
        </label>
        <label className="field" htmlFor="ops-deployment-record-note">
          <span>部署记录说明</span>
          <input
            id="ops-deployment-record-note"
            onChange={(event) => setDeploymentNote(event.target.value)}
            type="text"
            value={deploymentNote}
          />
        </label>
      </div>
      <div className="form-actions">
        <button
          className="secondary-button"
          disabled={runtimeSubmitting === 'DEPLOYMENT'}
          onClick={() => void handleRecordOperation('DEPLOYMENT')}
          type="button"
        >
          记录部署
        </button>
      </div>
      <div className="field-grid">
        <label className="field" htmlFor="ops-rollback-status">
          <span>回滚结果</span>
          <select
            id="ops-rollback-status"
            onChange={(event) => setRollbackStatus(event.target.value as DeploymentOperationStatus)}
            value={rollbackStatus}
          >
            <option value="RECORDED">已记录</option>
            <option value="SUCCEEDED">成功</option>
            <option value="FAILED">失败</option>
          </select>
        </label>
        <label className="field" htmlFor="ops-rollback-record-note">
          <span>回滚记录说明</span>
          <input
            id="ops-rollback-record-note"
            onChange={(event) => setRollbackNote(event.target.value)}
            type="text"
            value={rollbackNote}
          />
        </label>
      </div>
      <div className="form-actions">
        <button
          className="secondary-button"
          disabled={runtimeSubmitting === 'ROLLBACK'}
          onClick={() => void handleRecordOperation('ROLLBACK')}
          type="button"
        >
          记录回滚
        </button>
      </div>
    </>
  ) : (
    <p className="empty-state">保存部署目标后可记录健康检查、部署和回滚结果</p>
  )}
  {runtimeErrorMessage ? <p className="inline-error">{runtimeErrorMessage}</p> : null}
</section>
```

- [x] **步骤 8：实现 App 运行态处理函数**

修改 `App.tsx`：

1. import 增加：

```ts
  recordDeploymentOperation,
  runDeploymentHealthCheck,
  type DeploymentOperationInput,
```

2. 新增常量：

```ts
const operationsActorId = 'user-ops';
```

3. 新增处理函数：

```ts
async function handleRunDeploymentHealthCheck(targetId: string) {
  if (workbenchState.type !== 'ready') {
    return;
  }

  await runDeploymentHealthCheck(workbenchState.workbench.projectId, targetId, { actorId: operationsActorId });
  await refreshWorkbench({ keepCurrent: true });
}

async function handleRecordDeploymentOperation(targetId: string, input: Omit<DeploymentOperationInput, 'actorId'>) {
  if (workbenchState.type !== 'ready') {
    return;
  }

  await recordDeploymentOperation(workbenchState.workbench.projectId, targetId, {
    actorId: operationsActorId,
    ...input
  });
  await refreshWorkbench({ keepCurrent: true });
}
```

4. `OpsPanel` 调用改为：

```tsx
<OpsPanel
  deploymentTargets={workbench.deploymentTargets}
  deploymentRuntimeSummaries={workbench.deploymentRuntimeSummaries}
  onConfigureTarget={handleConfigureDeploymentTarget}
  onRunHealthCheck={handleRunDeploymentHealthCheck}
  onRecordDeploymentOperation={handleRecordDeploymentOperation}
/>
```

5. `InspectorPanel` 传入：

```tsx
deploymentRuntimeSummaries={workbench.deploymentRuntimeSummaries}
```

- [x] **步骤 9：实现右侧部署状态展示**

修改 `InspectorPanel.tsx`：

1. import 增加：

```ts
  DeploymentHealthStatus,
  DeploymentOperationStatus,
  DeploymentRuntimeSummary,
```

2. props 增加：

```ts
deploymentRuntimeSummaries: DeploymentRuntimeSummary[];
```

3. 新增标签映射：

```ts
const healthStatusLabels: Record<DeploymentHealthStatus, string> = {
  HEALTHY: '健康',
  UNHEALTHY: '非健康',
  UNREACHABLE: '不可达'
};

const operationStatusLabels: Record<DeploymentOperationStatus, string> = {
  RECORDED: '已记录',
  SUCCEEDED: '成功',
  FAILED: '失败'
};
```

4. 组件中新增：

```ts
const runtimeSummaryByTargetId = new Map(deploymentRuntimeSummaries.map((summary) => [summary.targetId, summary]));
```

5. 替换“部署状态”卡片中每个目标的展示内容：

```tsx
{deploymentTargets.map((target) => {
  const runtimeSummary = runtimeSummaryByTargetId.get(target.id);
  const health = runtimeSummary?.latestHealthCheck;
  const deployment = runtimeSummary?.latestDeploymentOperation;
  const rollback = runtimeSummary?.latestRollbackOperation;
  return (
    <li key={target.id}>
      <span>
        {target.environmentName} · {deploymentStatusLabels[target.status]} · 远程执行：
        {target.remoteExecuted ? '已触发' : '未触发'}
        {health ? ` · 健康：${healthStatusLabels[health.status]} · ${health.summary} · ${health.durationMillis} ms` : ''}
        {deployment ? ` · 部署：${operationStatusLabels[deployment.status]} · ${deployment.note}` : ''}
        {rollback ? ` · 回滚：${operationStatusLabels[rollback.status]} · ${rollback.note}` : ''}
      </span>
    </li>
  );
})}
```

- [x] **步骤 10：补充样式**

在 `App.css` 增加：

```css
.role-form[aria-label="部署运行记录"] {
  border-top: 1px solid #272b31;
}
```

- [x] **步骤 11：运行桌面 UI 测试验证通过**

运行：

```bash
cd desktop
npm test -- src/test/App.test.tsx
```

预期：`App.test.tsx` 通过。

- [x] **步骤 12：运行桌面类型检查和全量测试**

运行：

```bash
cd desktop
npm exec tsc -- --noEmit
```

预期：TypeScript 无错误。

运行：

```bash
cd desktop
npm test
```

预期：桌面端测试通过。

- [x] **步骤 13：提交**

```bash
git add desktop/src/api/client.ts
git add desktop/src/api/client.test.ts
git add desktop/src/components/OpsPanel.tsx
git add desktop/src/components/InspectorPanel.tsx
git add desktop/src/App.tsx
git add desktop/src/App.css
git add desktop/src/test/App.test.tsx
git commit -m "feat: 接入部署健康检查桌面交互"
```

---

### 任务 6：整体验证和文档更新

**文件：**

- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-24-matrixcode-deployment-health-operations.md`

- [x] **步骤 1：运行服务端全量测试**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

运行后统计 Surefire 汇总：

```bash
awk '/Tests run:/ {split($3,a,","); split($5,b,","); split($7,c,","); split($9,d,","); tests+=a[1]; failures+=b[1]; errors+=c[1]; skipped+=d[1]} END {printf "Tests run: %d, Failures: %d, Errors: %d, Skipped: %d\n", tests, failures, errors, skipped}' server/target/surefire-reports/*.txt
```

预期：服务端全部测试通过，失败数和错误数均为 0。

- [x] **步骤 2：运行桌面端测试和构建**

运行：

```bash
cd desktop
npm test
```

预期：Vitest 通过。

运行：

```bash
cd desktop
npm run build
```

预期：`tsc --noEmit` 和 `vite build` 通过。

运行：

```bash
cd desktop
npm run tauri:build -- --help
```

预期：Tauri CLI 帮助正常输出，退出码 0。

- [x] **步骤 3：启动服务端做运行态验证**

确认 8080 未被占用：

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

预期：无输出。

启动服务端：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

另一个终端配置部署目标：

```bash
TARGET_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/deployments/targets \
  -H 'Content-Type: application/json' \
  -d '{"environmentName":"本地验证环境","environmentUrl":"http://localhost:8080","sshAddress":"deploy@localhost","deployNote":"只记录部署说明，不执行 SSH","healthCheckUrl":"http://localhost:8080/actuator/health","rollbackNote":"只记录回滚说明，不执行远程命令"}')
TARGET_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).id)' "$TARGET_JSON")
```

运行健康检查：

```bash
curl -sS -X POST "http://localhost:8080/api/projects/demo/deployments/targets/${TARGET_ID}/health-checks" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops"}'
```

预期：返回 `HEALTHY`，`httpStatus` 为 200，摘要包含 `HTTP 200`。

记录部署：

```bash
curl -sS -X POST "http://localhost:8080/api/projects/demo/deployments/targets/${TARGET_ID}/operations" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops","type":"DEPLOYMENT","status":"SUCCEEDED","note":"本地验证部署记录"}'
```

预期：返回 `DEPLOYMENT` 和 `SUCCEEDED`。

记录回滚：

```bash
curl -sS -X POST "http://localhost:8080/api/projects/demo/deployments/targets/${TARGET_ID}/operations" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops","type":"ROLLBACK","status":"RECORDED","note":"本地验证回滚记录"}'
```

预期：返回 `ROLLBACK` 和 `RECORDED`。

查看工作台：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

预期：响应包含 `deploymentRuntimeSummaries[0].latestHealthCheck.status=HEALTHY`，并包含最新部署和回滚记录。

停止服务端后再次确认端口：

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

预期：无输出。

- [x] **步骤 4：更新本地运行文档**

在 `docs/development/local-run.md` 的第五阶段验证后新增：

````markdown
## 第六阶段部署健康检查与运维记录验证

服务端启动后，先配置部署目标：

```bash
TARGET_JSON=$(curl -sS -X POST http://localhost:8080/api/projects/demo/deployments/targets \
  -H 'Content-Type: application/json' \
  -d '{"environmentName":"本地验证环境","environmentUrl":"http://localhost:8080","sshAddress":"deploy@localhost","deployNote":"只记录部署说明，不执行 SSH","healthCheckUrl":"http://localhost:8080/actuator/health","rollbackNote":"只记录回滚说明，不执行远程命令"}')
TARGET_ID=$(node -e 'console.log(JSON.parse(process.argv[1]).id)' "$TARGET_JSON")
```

运行健康检查：

```bash
curl -sS -X POST "http://localhost:8080/api/projects/demo/deployments/targets/${TARGET_ID}/health-checks" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops"}'
```

响应应包含 `HEALTHY`、`HTTP 200` 和 `durationMillis`。

记录部署和回滚：

```bash
curl -sS -X POST "http://localhost:8080/api/projects/demo/deployments/targets/${TARGET_ID}/operations" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops","type":"DEPLOYMENT","status":"SUCCEEDED","note":"本地验证部署记录"}'

curl -sS -X POST "http://localhost:8080/api/projects/demo/deployments/targets/${TARGET_ID}/operations" \
  -H 'Content-Type: application/json' \
  -d '{"actorId":"user-ops","type":"ROLLBACK","status":"RECORDED","note":"本地验证回滚记录"}'
```

工作台响应中的 `deploymentRuntimeSummaries` 应包含最新健康检查、部署记录和回滚记录。以上操作不会执行 SSH 或远程部署脚本。
````

- [x] **步骤 5：勾选计划并追加验证记录**

在本计划末尾新增“第六阶段验证记录”，记录：

- 服务端全量测试统计。
- 桌面端测试、构建和 Tauri 命令入口。
- 运行态健康检查、部署记录、回滚记录和工作台摘要结果。
- 端口清理结果。

把任务 1 到任务 6 的完成步骤勾选为 `[x]`。

- [x] **步骤 6：检查文档和 diff**

运行：

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|\\bplace(holder)\\b|\\bS[u]mmary\\b|\\bG[o]als\\b|\\bAcceptance C[r]iteria\\b" docs
```

预期：无输出。

运行：

```bash
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs
```

预期：无输出。

运行：

```bash
git diff --check
```

预期：无输出，退出码 0。

- [x] **步骤 7：提交**

```bash
git add docs/development/local-run.md
git add docs/superpowers/plans/2026-06-24-matrixcode-deployment-health-operations.md
git commit -m "docs: 记录第六阶段部署健康检查验证"
```

---

## 第六阶段验证记录

- 服务端全量测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过，Surefire 汇总为 `Tests run: 160, Failures: 0, Errors: 0, Skipped: 10`。
- 桌面端验证：`cd desktop && npm test` 通过，2 个测试文件、41 个测试用例全部通过；`cd desktop && npm run build` 通过；`cd desktop && npm run tauri:build -- --help` 返回 Tauri build 帮助并以 0 退出。
- 运行态 API 验证：部署目标配置后 `remoteExecuted=false`；健康检查结果为 `HEALTHY`、HTTP 状态码 `200`、摘要 `HTTP 200`；部署记录为 `DEPLOYMENT/SUCCEEDED`，备注 `本地验证部署记录`；回滚记录为 `ROLLBACK/RECORDED`，备注 `本地验证回滚记录`。
- 工作台聚合验证：`deploymentRuntimeSummaries` 返回最新健康检查、最新部署记录和最新回滚记录；工作台健康状态为 `HEALTHY`，部署状态为 `SUCCEEDED`，回滚状态为 `RECORDED`。
- 桌面真实 API 验证：真实页面显示 `运维工作区`、`部署运行记录`、`运行健康检查`、`部署状态`，右侧状态文本包含 `健康：健康 · HTTP 200`、`部署：成功 · 本地验证部署记录`、`回滚：已记录 · 本地验证回滚记录`。
- 端口清理：运行态验证前 `lsof -nP -iTCP:8080 -sTCP:LISTEN` 无输出；服务端停止后再次执行该命令无输出。

## 自检记录

- 规格覆盖：健康检查、部署记录、回滚记录、工作台摘要、桌面运维面板、右侧部署状态、运行态验证和安全边界均有对应任务。
- 范围控制：真实 SSH、远程部署脚本、凭证、定时探测、告警、多成员权限、数据库持久化和长任务队列均未纳入本计划。
- 类型一致性：服务端 `DeploymentHealthCheck`、`DeploymentOperationRecord`、`DeploymentRuntimeSummary` 与桌面端同名类型字段保持一致。
- 安全边界：健康检查只使用已保存的 `healthCheckUrl`；部署和回滚只记录，不触发远程执行。
- 验证命令：服务端命令均使用 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store`。
