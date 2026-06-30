# MatrixCode MVP 纵切实现计划

> **给智能体执行者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 按任务执行本计划。步骤使用复选框（`- [ ]`）跟踪。

**目标：** 构建 MatrixCode 第一条可运行纵切：团队服务器、文档/工作流/审批核心、上下文与用量统计、实时事件、执行代理接口和桌面工作台壳。

**架构：** 使用 Java 21 + Spring Boot 3.5.15 构建模块化单体服务端，先用清晰的领域服务和内存仓储打通业务闭环，再保留 PostgreSQL/Redis 的运行配置入口。桌面端使用 Tauri 2 + React + TypeScript 构建可连接服务器的工作台壳，执行代理先通过服务端 API 上报心跳和执行结果。

**技术栈：** Java 21、Spring Boot 3.5.15、Maven、JUnit 5、React 19.2.7、TypeScript 6.0.3、Vite 8.1.0、Tauri CLI 2.11.3、Vitest 4.1.9、Docker Compose。

---

## 范围检查

设计规格覆盖完整团队平台，包含身份、项目、文档、状态流、角色智能体、模型网关、执行代理、Docker/SSH、桌面 UI 和分发。一次计划全部实现会过大。本计划只实现第一条可运行纵切：

- 服务端能启动，并提供健康检查。
- 能创建项目、冻结文档、推进工作流、记录 Bug 和审批事件。
- 能生成角色上下文清单，展示哪些产物进入模型请求。
- 能记录模型用量、缓存命中/未命中和预估费用。
- 能通过 SSE 推送项目事件。
- 能接收执行代理心跳和执行结果。
- 桌面端能连接服务端，展示项目、角色、事件、缓存和用量数据。
- Docker Compose 能启动团队服务器依赖项。

完整模型调用、真实文件编辑、SSH 执行、Docker Compose 远程编排、完整登录鉴权、生产级持久化将在后续计划中拆分。

## 文件结构

```text
.
├── pom.xml
├── server/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/matrixcode/
│       │   ├── MatrixCodeServerApplication.java
│       │   ├── common/
│       │   ├── workflow/
│       │   ├── document/
│       │   ├── approval/
│       │   ├── usage/
│       │   ├── context/
│       │   ├── realtime/
│       │   └── execution/
│       ├── main/resources/application.yml
│       └── test/java/com/matrixcode/
├── desktop/
│   ├── package.json
│   ├── index.html
│   ├── vite.config.ts
│   ├── tsconfig.json
│   └── src/
│       ├── App.tsx
│       ├── api/client.ts
│       ├── components/
│       └── test/
├── docker-compose.yml
└── docs/
    ├── superpowers/specs/2026-06-23-matrixcode-design.md
    └── development/local-run.md
```

---

## 任务 1：创建服务端 Maven 骨架

**文件：**

- 创建：`pom.xml`
- 创建：`server/pom.xml`
- 创建：`server/src/test/java/com/matrixcode/MatrixCodeServerApplicationTest.java`
- 创建：`server/src/main/java/com/matrixcode/MatrixCodeServerApplication.java`
- 创建：`server/src/main/resources/application.yml`

- [x] **步骤 1：创建 Maven 根工程配置**

创建 `pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.matrixcode</groupId>
    <artifactId>matrixcode</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>server</module>
    </modules>
</project>
```

- [x] **步骤 2：创建服务端 Maven 配置**

创建 `server/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.15</version>
        <relativePath/>
    </parent>

    <groupId>com.matrixcode</groupId>
    <artifactId>matrixcode-server</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>matrixcode-server</name>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [x] **步骤 3：先写失败的启动测试**

创建 `server/src/test/java/com/matrixcode/MatrixCodeServerApplicationTest.java`：

```java
package com.matrixcode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MatrixCodeServerApplicationTest {

    @Test
    void 应用上下文可以启动() {
    }
}
```

- [x] **步骤 4：运行测试并确认失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：失败，错误包含找不到 `com.matrixcode.MatrixCodeServerApplication` 或无法加载 Spring Boot 配置类。

- [x] **步骤 5：实现最小启动类和配置**

创建 `server/src/main/java/com/matrixcode/MatrixCodeServerApplication.java`：

```java
package com.matrixcode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MatrixCodeServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatrixCodeServerApplication.class, args);
    }
}
```

创建 `server/src/main/resources/application.yml`：

```yaml
spring:
  application:
    name: matrixcode-server

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [x] **步骤 6：运行测试并确认通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：测试通过。

- [x] **步骤 7：提交**

```bash
git add pom.xml server
git commit -m "feat: 创建 MatrixCode 服务端骨架"
```

---

## 任务 2：实现工作流状态机

**文件：**

- 创建：`server/src/test/java/com/matrixcode/workflow/WorkflowServiceTest.java`
- 创建：`server/src/main/java/com/matrixcode/workflow/domain/WorkflowState.java`
- 创建：`server/src/main/java/com/matrixcode/workflow/domain/WorkflowEventType.java`
- 创建：`server/src/main/java/com/matrixcode/workflow/domain/WorkflowItem.java`
- 创建：`server/src/main/java/com/matrixcode/workflow/domain/WorkflowEvent.java`
- 创建：`server/src/main/java/com/matrixcode/workflow/application/WorkflowService.java`

- [x] **步骤 1：写失败的工作流测试**

创建 `server/src/test/java/com/matrixcode/workflow/WorkflowServiceTest.java`：

```java
package com.matrixcode.workflow;

import com.matrixcode.workflow.application.WorkflowService;
import com.matrixcode.workflow.domain.WorkflowEventType;
import com.matrixcode.workflow.domain.WorkflowState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowServiceTest {

    private final WorkflowService service = new WorkflowService();

    @Test
    void 需求文档可以从草稿推进到已冻结() {
        var item = service.createItem("project-1", "PRD-001");

        var reviewed = service.apply(item.id(), WorkflowEventType.SUBMIT_REVIEW, "user-product");
        var frozen = service.apply(reviewed.id(), WorkflowEventType.FREEZE, "user-product");

        assertThat(frozen.state()).isEqualTo(WorkflowState.FROZEN);
        assertThat(service.eventsOf(frozen.id())).hasSize(2);
    }

    @Test
    void 草稿不能直接进入执行中() {
        var item = service.createItem("project-1", "PRD-001");

        assertThatThrownBy(() -> service.apply(item.id(), WorkflowEventType.START_WORK, "user-dev"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法状态流转");
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkflowServiceTest test
```

预期：失败，原因是 `WorkflowService` 和相关领域类型不存在。

- [x] **步骤 3：实现状态枚举和事件类型**

创建 `WorkflowState.java`：

```java
package com.matrixcode.workflow.domain;

public enum WorkflowState {
    DRAFT,
    REVIEW_PENDING,
    FROZEN,
    IN_PROGRESS,
    ACCEPTANCE_PENDING,
    DONE
}
```

创建 `WorkflowEventType.java`：

```java
package com.matrixcode.workflow.domain;

public enum WorkflowEventType {
    SUBMIT_REVIEW,
    FREEZE,
    START_WORK,
    SUBMIT_ACCEPTANCE,
    ACCEPT,
    REJECT
}
```

- [x] **步骤 4：实现工作流记录**

创建 `WorkflowItem.java`：

```java
package com.matrixcode.workflow.domain;

public record WorkflowItem(
        String id,
        String projectId,
        String title,
        WorkflowState state
) {
    public WorkflowItem withState(WorkflowState nextState) {
        return new WorkflowItem(id, projectId, title, nextState);
    }
}
```

创建 `WorkflowEvent.java`：

```java
package com.matrixcode.workflow.domain;

import java.time.Instant;

public record WorkflowEvent(
        String id,
        String itemId,
        WorkflowEventType type,
        WorkflowState fromState,
        WorkflowState toState,
        String actorId,
        Instant occurredAt
) {
}
```

- [x] **步骤 5：实现最小工作流服务**

创建 `WorkflowService.java`：

```java
package com.matrixcode.workflow.application;

import com.matrixcode.workflow.domain.WorkflowEvent;
import com.matrixcode.workflow.domain.WorkflowEventType;
import com.matrixcode.workflow.domain.WorkflowItem;
import com.matrixcode.workflow.domain.WorkflowState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WorkflowService {

    private final Map<String, WorkflowItem> items = new ConcurrentHashMap<>();
    private final Map<String, List<WorkflowEvent>> events = new ConcurrentHashMap<>();

    public WorkflowItem createItem(String projectId, String title) {
        var item = new WorkflowItem(UUID.randomUUID().toString(), projectId, title, WorkflowState.DRAFT);
        items.put(item.id(), item);
        events.put(item.id(), new ArrayList<>());
        return item;
    }

    public WorkflowItem apply(String itemId, WorkflowEventType eventType, String actorId) {
        var current = items.get(itemId);
        if (current == null) {
            throw new IllegalArgumentException("工作项不存在：" + itemId);
        }
        var nextState = nextState(current.state(), eventType);
        var next = current.withState(nextState);
        items.put(itemId, next);
        events.get(itemId).add(new WorkflowEvent(
                UUID.randomUUID().toString(),
                itemId,
                eventType,
                current.state(),
                nextState,
                actorId,
                Instant.now()
        ));
        return next;
    }

    public List<WorkflowEvent> eventsOf(String itemId) {
        return List.copyOf(events.getOrDefault(itemId, List.of()));
    }

    private WorkflowState nextState(WorkflowState state, WorkflowEventType eventType) {
        return switch (state) {
            case DRAFT -> switch (eventType) {
                case SUBMIT_REVIEW -> WorkflowState.REVIEW_PENDING;
                default -> throw illegal(state, eventType);
            };
            case REVIEW_PENDING -> switch (eventType) {
                case FREEZE -> WorkflowState.FROZEN;
                case REJECT -> WorkflowState.DRAFT;
                default -> throw illegal(state, eventType);
            };
            case FROZEN -> switch (eventType) {
                case START_WORK -> WorkflowState.IN_PROGRESS;
                default -> throw illegal(state, eventType);
            };
            case IN_PROGRESS -> switch (eventType) {
                case SUBMIT_ACCEPTANCE -> WorkflowState.ACCEPTANCE_PENDING;
                default -> throw illegal(state, eventType);
            };
            case ACCEPTANCE_PENDING -> switch (eventType) {
                case ACCEPT -> WorkflowState.DONE;
                case REJECT -> WorkflowState.IN_PROGRESS;
                default -> throw illegal(state, eventType);
            };
            case DONE -> throw illegal(state, eventType);
        };
    }

    private IllegalStateException illegal(WorkflowState state, WorkflowEventType eventType) {
        return new IllegalStateException("非法状态流转：" + state + " -> " + eventType);
    }
}
```

- [x] **步骤 6：运行测试并提交**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkflowServiceTest test
git add server/src/main/java/com/matrixcode/workflow server/src/test/java/com/matrixcode/workflow
git commit -m "feat: 实现工作流状态机"
```

预期：测试通过。

---

## 任务 3：实现文档版本和冻结机制

**文件：**

- 创建：`server/src/test/java/com/matrixcode/document/DocumentServiceTest.java`
- 创建：`server/src/main/java/com/matrixcode/document/domain/DocumentType.java`
- 创建：`server/src/main/java/com/matrixcode/document/domain/DocumentState.java`
- 创建：`server/src/main/java/com/matrixcode/document/domain/DocumentVersion.java`
- 创建：`server/src/main/java/com/matrixcode/document/application/DocumentService.java`

- [x] **步骤 1：写失败的文档冻结测试**

创建 `DocumentServiceTest.java`：

```java
package com.matrixcode.document;

import com.matrixcode.document.application.DocumentService;
import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentServiceTest {

    private final DocumentService service = new DocumentService();

    @Test
    void 冻结文档后不能修改原版本() {
        var draft = service.createDraft("project-1", DocumentType.PRD, "支付重构 PRD", "初始需求");
        var frozen = service.freeze(draft.id(), "user-product");

        assertThat(frozen.state()).isEqualTo(DocumentState.FROZEN);
        assertThatThrownBy(() -> service.updateContent(frozen.id(), "修改冻结内容"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冻结版本不能修改");
    }

    @Test
    void 需求变更会基于冻结版本创建新草稿() {
        var draft = service.createDraft("project-1", DocumentType.PRD, "支付重构 PRD", "初始需求");
        var frozen = service.freeze(draft.id(), "user-product");
        var changeDraft = service.createChangeDraft(frozen.id(), "新增退款规则");

        assertThat(changeDraft.state()).isEqualTo(DocumentState.DRAFT);
        assertThat(changeDraft.parentVersionId()).isEqualTo(frozen.id());
        assertThat(changeDraft.version()).isEqualTo(2);
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DocumentServiceTest test
```

预期：失败，原因是文档服务和领域对象不存在。

- [x] **步骤 3：实现文档领域对象**

创建 `DocumentType.java`：

```java
package com.matrixcode.document.domain;

public enum DocumentType {
    PRD,
    UI_BRIEF,
    API_DOC,
    DATABASE_SCRIPT,
    DEPLOYMENT_DOC,
    QA_REPORT
}
```

创建 `DocumentState.java`：

```java
package com.matrixcode.document.domain;

public enum DocumentState {
    DRAFT,
    REVIEW_PENDING,
    FROZEN
}
```

创建 `DocumentVersion.java`：

```java
package com.matrixcode.document.domain;

import java.time.Instant;

public record DocumentVersion(
        String id,
        String projectId,
        DocumentType type,
        String title,
        String content,
        int version,
        DocumentState state,
        String parentVersionId,
        String frozenBy,
        Instant frozenAt
) {
    public DocumentVersion withContent(String nextContent) {
        return new DocumentVersion(id, projectId, type, title, nextContent, version, state, parentVersionId, frozenBy, frozenAt);
    }

    public DocumentVersion freeze(String actorId, Instant now) {
        return new DocumentVersion(id, projectId, type, title, content, version, DocumentState.FROZEN, parentVersionId, actorId, now);
    }
}
```

- [x] **步骤 4：实现文档服务**

创建 `DocumentService.java`：

```java
package com.matrixcode.document.application;

import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.document.domain.DocumentType;
import com.matrixcode.document.domain.DocumentVersion;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DocumentService {

    private final Map<String, DocumentVersion> documents = new ConcurrentHashMap<>();

    public DocumentVersion createDraft(String projectId, DocumentType type, String title, String content) {
        var document = new DocumentVersion(
                UUID.randomUUID().toString(),
                projectId,
                type,
                title,
                content,
                1,
                DocumentState.DRAFT,
                null,
                null,
                null
        );
        documents.put(document.id(), document);
        return document;
    }

    public DocumentVersion updateContent(String documentId, String content) {
        var current = require(documentId);
        if (current.state() == DocumentState.FROZEN) {
            throw new IllegalStateException("冻结版本不能修改：" + documentId);
        }
        var updated = current.withContent(content);
        documents.put(documentId, updated);
        return updated;
    }

    public DocumentVersion freeze(String documentId, String actorId) {
        var frozen = require(documentId).freeze(actorId, Instant.now());
        documents.put(documentId, frozen);
        return frozen;
    }

    public DocumentVersion createChangeDraft(String frozenVersionId, String content) {
        var parent = require(frozenVersionId);
        if (parent.state() != DocumentState.FROZEN) {
            throw new IllegalStateException("只能基于冻结版本创建变更草稿：" + frozenVersionId);
        }
        var draft = new DocumentVersion(
                UUID.randomUUID().toString(),
                parent.projectId(),
                parent.type(),
                parent.title(),
                content,
                parent.version() + 1,
                DocumentState.DRAFT,
                parent.id(),
                null,
                null
        );
        documents.put(draft.id(), draft);
        return draft;
    }

    private DocumentVersion require(String documentId) {
        var document = documents.get(documentId);
        if (document == null) {
            throw new IllegalArgumentException("文档版本不存在：" + documentId);
        }
        return document;
    }
}
```

- [x] **步骤 5：运行测试并提交**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=DocumentServiceTest test
git add server/src/main/java/com/matrixcode/document server/src/test/java/com/matrixcode/document
git commit -m "feat: 实现文档版本冻结机制"
```

预期：测试通过。

---

## 任务 4：实现审批策略和审计记录

**文件：**

- 创建：`server/src/test/java/com/matrixcode/approval/ApprovalPolicyTest.java`
- 创建：`server/src/main/java/com/matrixcode/approval/domain/ToolAction.java`
- 创建：`server/src/main/java/com/matrixcode/approval/domain/ApprovalDecision.java`
- 创建：`server/src/main/java/com/matrixcode/approval/domain/AuditRecord.java`
- 创建：`server/src/main/java/com/matrixcode/approval/application/ApprovalPolicy.java`
- 创建：`server/src/main/java/com/matrixcode/approval/application/AuditService.java`

- [x] **步骤 1：写失败的审批策略测试**

创建 `ApprovalPolicyTest.java`：

```java
package com.matrixcode.approval;

import com.matrixcode.approval.application.ApprovalPolicy;
import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.ToolAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalPolicyTest {

    private final ApprovalPolicy policy = new ApprovalPolicy();

    @Test
    void 工作区内测试命令可以自动执行() {
        var action = new ToolAction("task-1", "user-dev", "SHELL", "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store test", "/repo/payment", false);

        assertThat(policy.decide(action)).isEqualTo(ApprovalDecision.ALLOW);
    }

    @Test
    void ssh和删除动作必须人工确认() {
        var ssh = new ToolAction("task-1", "user-ops", "SSH", "systemctl restart payment", "/repo/payment", true);
        var delete = new ToolAction("task-1", "user-dev", "SHELL", "rm -rf /repo/payment/build", "/repo/payment", true);

        assertThat(policy.decide(ssh)).isEqualTo(ApprovalDecision.ASK);
        assertThat(policy.decide(delete)).isEqualTo(ApprovalDecision.ASK);
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ApprovalPolicyTest test
```

预期：失败，原因是审批类型不存在。

- [x] **步骤 3：实现审批领域类型**

创建 `ApprovalDecision.java`：

```java
package com.matrixcode.approval.domain;

public enum ApprovalDecision {
    ALLOW,
    ASK,
    DENY
}
```

创建 `ToolAction.java`：

```java
package com.matrixcode.approval.domain;

public record ToolAction(
        String taskId,
        String actorId,
        String toolType,
        String command,
        String workspacePath,
        boolean dangerous
) {
}
```

创建 `AuditRecord.java`：

```java
package com.matrixcode.approval.domain;

import java.time.Instant;

public record AuditRecord(
        String id,
        String taskId,
        String actorId,
        String toolType,
        String summary,
        ApprovalDecision decision,
        Instant occurredAt
) {
}
```

- [x] **步骤 4：实现审批策略和审计服务**

创建 `ApprovalPolicy.java`：

```java
package com.matrixcode.approval.application;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.ToolAction;

public class ApprovalPolicy {

    public ApprovalDecision decide(ToolAction action) {
        if (action.dangerous()) {
            return ApprovalDecision.ASK;
        }
        if ("SSH".equalsIgnoreCase(action.toolType())) {
            return ApprovalDecision.ASK;
        }
        if (action.command() != null && action.command().contains("rm -rf")) {
            return ApprovalDecision.ASK;
        }
        return ApprovalDecision.ALLOW;
    }
}
```

创建 `AuditService.java`：

```java
package com.matrixcode.approval.application;

import com.matrixcode.approval.domain.ApprovalDecision;
import com.matrixcode.approval.domain.AuditRecord;
import com.matrixcode.approval.domain.ToolAction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AuditService {

    private final List<AuditRecord> records = new ArrayList<>();

    public AuditRecord record(ToolAction action, ApprovalDecision decision) {
        var record = new AuditRecord(
                UUID.randomUUID().toString(),
                action.taskId(),
                action.actorId(),
                action.toolType(),
                action.command(),
                decision,
                Instant.now()
        );
        records.add(record);
        return record;
    }

    public List<AuditRecord> records() {
        return List.copyOf(records);
    }
}
```

- [x] **步骤 5：运行测试并提交**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ApprovalPolicyTest test
git add server/src/main/java/com/matrixcode/approval server/src/test/java/com/matrixcode/approval
git commit -m "feat: 实现审批策略和审计记录"
```

预期：测试通过。

---

## 任务 5：实现模型用量和缓存费用统计

**文件：**

- 创建：`server/src/test/java/com/matrixcode/usage/UsageCalculatorTest.java`
- 创建：`server/src/main/java/com/matrixcode/usage/domain/ModelPrice.java`
- 创建：`server/src/main/java/com/matrixcode/usage/domain/UsageRecord.java`
- 创建：`server/src/main/java/com/matrixcode/usage/application/UsageCalculator.java`

- [x] **步骤 1：写失败的用量计算测试**

创建 `UsageCalculatorTest.java`：

```java
package com.matrixcode.usage;

import com.matrixcode.usage.application.UsageCalculator;
import com.matrixcode.usage.domain.ModelPrice;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsageCalculatorTest {

    @Test
    void 缓存命中token按更低单价计费() {
        var calculator = new UsageCalculator();
        var price = new ModelPrice("deepseek-v4-pro", "CNY", 0.025, 3.0, 6.0);

        var record = calculator.calculate("role-dev", price, 80_000, 20_000, 10_000);

        assertThat(record.cacheHitRate()).isEqualTo(0.80);
        assertThat(record.estimatedCost()).isEqualTo(0.122);
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=UsageCalculatorTest test
```

预期：失败，原因是用量统计类型不存在。

- [x] **步骤 3：实现价格和用量记录**

创建 `ModelPrice.java`：

```java
package com.matrixcode.usage.domain;

public record ModelPrice(
        String model,
        String currency,
        double cacheHitPerMillion,
        double cacheMissInputPerMillion,
        double outputPerMillion
) {
}
```

创建 `UsageRecord.java`：

```java
package com.matrixcode.usage.domain;

public record UsageRecord(
        String roleSessionId,
        String model,
        long cacheHitTokens,
        long cacheMissInputTokens,
        long outputTokens,
        double cacheHitRate,
        double estimatedCost,
        String currency
) {
}
```

- [x] **步骤 4：实现费用计算器**

创建 `UsageCalculator.java`：

```java
package com.matrixcode.usage.application;

import com.matrixcode.usage.domain.ModelPrice;
import com.matrixcode.usage.domain.UsageRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class UsageCalculator {

    public UsageRecord calculate(String roleSessionId, ModelPrice price, long cacheHitTokens, long cacheMissInputTokens, long outputTokens) {
        long promptTokens = cacheHitTokens + cacheMissInputTokens;
        double hitRate = promptTokens == 0 ? 0.0 : ((double) cacheHitTokens) / promptTokens;
        double cost = cacheHitTokens / 1_000_000.0 * price.cacheHitPerMillion()
                + cacheMissInputTokens / 1_000_000.0 * price.cacheMissInputPerMillion()
                + outputTokens / 1_000_000.0 * price.outputPerMillion();
        double roundedCost = BigDecimal.valueOf(cost).setScale(3, RoundingMode.HALF_UP).doubleValue();
        double roundedHitRate = BigDecimal.valueOf(hitRate).setScale(2, RoundingMode.HALF_UP).doubleValue();
        return new UsageRecord(roleSessionId, price.model(), cacheHitTokens, cacheMissInputTokens, outputTokens, roundedHitRate, roundedCost, price.currency());
    }
}
```

- [x] **步骤 5：运行测试并提交**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=UsageCalculatorTest test
git add server/src/main/java/com/matrixcode/usage server/src/test/java/com/matrixcode/usage
git commit -m "feat: 实现模型用量和缓存统计"
```

预期：测试通过。

---

## 任务 6：实现角色上下文清单

**文件：**

- 创建：`server/src/test/java/com/matrixcode/context/ContextEngineTest.java`
- 创建：`server/src/main/java/com/matrixcode/context/domain/ContextBlock.java`
- 创建：`server/src/main/java/com/matrixcode/context/domain/ContextManifest.java`
- 创建：`server/src/main/java/com/matrixcode/context/application/ContextEngine.java`

- [x] **步骤 1：写失败的上下文门禁测试**

创建 `ContextEngineTest.java`：

```java
package com.matrixcode.context;

import com.matrixcode.context.application.ContextEngine;
import com.matrixcode.context.domain.ContextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextEngineTest {

    @Test
    void 开发角色只接收冻结文档和当前状态事件() {
        var engine = new ContextEngine();
        var manifest = engine.build("DEVELOPMENT", List.of(
                new ContextBlock("PRODUCT_CHAT", "产品长对话", false),
                new ContextBlock("FROZEN_PRD", "PRD v1", true),
                new ContextBlock("CURRENT_EVENT", "任务进入开发", true)
        ));

        assertThat(manifest.blocks()).extracting(ContextBlock::type)
                .containsExactly("FROZEN_PRD", "CURRENT_EVENT");
        assertThat(manifest.omittedTypes()).containsExactly("PRODUCT_CHAT");
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ContextEngineTest test
```

预期：失败，原因是上下文引擎不存在。

- [x] **步骤 3：实现上下文类型**

创建 `ContextBlock.java`：

```java
package com.matrixcode.context.domain;

public record ContextBlock(
        String type,
        String summary,
        boolean allowedByGate
) {
}
```

创建 `ContextManifest.java`：

```java
package com.matrixcode.context.domain;

import java.util.List;

public record ContextManifest(
        String role,
        List<ContextBlock> blocks,
        List<String> omittedTypes
) {
}
```

- [x] **步骤 4：实现上下文引擎**

创建 `ContextEngine.java`：

```java
package com.matrixcode.context.application;

import com.matrixcode.context.domain.ContextBlock;
import com.matrixcode.context.domain.ContextManifest;

import java.util.List;

public class ContextEngine {

    public ContextManifest build(String role, List<ContextBlock> candidates) {
        var allowed = candidates.stream()
                .filter(ContextBlock::allowedByGate)
                .toList();
        var omitted = candidates.stream()
                .filter(block -> !block.allowedByGate())
                .map(ContextBlock::type)
                .toList();
        return new ContextManifest(role, allowed, omitted);
    }
}
```

- [x] **步骤 5：运行测试并提交**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ContextEngineTest test
git add server/src/main/java/com/matrixcode/context server/src/test/java/com/matrixcode/context
git commit -m "feat: 实现角色上下文清单"
```

预期：测试通过。

---

## 任务 7：实现项目事件实时流

**文件：**

- 创建：`server/src/test/java/com/matrixcode/realtime/ProjectEventStreamTest.java`
- 创建：`server/src/main/java/com/matrixcode/realtime/domain/ProjectEvent.java`
- 创建：`server/src/main/java/com/matrixcode/realtime/application/ProjectEventBus.java`
- 创建：`server/src/main/java/com/matrixcode/realtime/api/ProjectEventController.java`

- [x] **步骤 1：写失败的事件总线测试**

创建 `ProjectEventStreamTest.java`：

```java
package com.matrixcode.realtime;

import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.domain.ProjectEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectEventStreamTest {

    @Test
    void 发布项目事件后可以读取最近事件() {
        var bus = new ProjectEventBus();
        bus.publish(new ProjectEvent("project-1", "WORKFLOW_CHANGED", "需求已冻结"));

        assertThat(bus.recent("project-1")).hasSize(1);
        assertThat(bus.recent("project-1").getFirst().message()).isEqualTo("需求已冻结");
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ProjectEventStreamTest test
```

预期：失败，原因是实时事件类型不存在。

- [x] **步骤 3：实现事件和事件总线**

创建 `ProjectEvent.java`：

```java
package com.matrixcode.realtime.domain;

import java.time.Instant;
import java.util.UUID;

public record ProjectEvent(
        String id,
        String projectId,
        String type,
        String message,
        Instant occurredAt
) {
    public ProjectEvent(String projectId, String type, String message) {
        this(UUID.randomUUID().toString(), projectId, type, message, Instant.now());
    }
}
```

创建 `ProjectEventBus.java`：

```java
package com.matrixcode.realtime.application;

import com.matrixcode.realtime.domain.ProjectEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ProjectEventBus {

    private final Map<String, List<ProjectEvent>> events = new ConcurrentHashMap<>();

    public void publish(ProjectEvent event) {
        events.computeIfAbsent(event.projectId(), ignored -> new ArrayList<>()).add(event);
    }

    public List<ProjectEvent> recent(String projectId) {
        return List.copyOf(events.getOrDefault(projectId, List.of()));
    }
}
```

- [x] **步骤 4：实现只读 REST 接口**

创建 `ProjectEventController.java`：

```java
package com.matrixcode.realtime.api;

import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.realtime.domain.ProjectEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/events")
public class ProjectEventController {

    private final ProjectEventBus eventBus;

    public ProjectEventController(ProjectEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @GetMapping
    public List<ProjectEvent> recent(@PathVariable String projectId) {
        return eventBus.recent(projectId);
    }
}
```

- [x] **步骤 5：运行测试并提交**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ProjectEventStreamTest test
git add server/src/main/java/com/matrixcode/realtime server/src/test/java/com/matrixcode/realtime
git commit -m "feat: 实现项目事件实时基础"
```

预期：测试通过。

---

## 任务 8：实现执行代理心跳和结果上报接口

**文件：**

- 创建：`server/src/test/java/com/matrixcode/execution/ExecutionGatewayTest.java`
- 创建：`server/src/main/java/com/matrixcode/execution/domain/AgentHeartbeat.java`
- 创建：`server/src/main/java/com/matrixcode/execution/domain/ExecutionResult.java`
- 创建：`server/src/main/java/com/matrixcode/execution/application/ExecutionGateway.java`
- 创建：`server/src/main/java/com/matrixcode/execution/api/ExecutionAgentController.java`

- [x] **步骤 1：写失败的执行网关测试**

创建 `ExecutionGatewayTest.java`：

```java
package com.matrixcode.execution;

import com.matrixcode.execution.application.ExecutionGateway;
import com.matrixcode.execution.domain.AgentHeartbeat;
import com.matrixcode.execution.domain.ExecutionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionGatewayTest {

    @Test
    void 执行代理可以上报心跳和任务结果() {
        var gateway = new ExecutionGateway();

        gateway.heartbeat(new AgentHeartbeat("agent-1", "project-1", "user-dev", "ONLINE"));
        gateway.report(new ExecutionResult("task-1", "agent-1", "SUCCESS", "/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store test 通过"));

        assertThat(gateway.lastHeartbeat("agent-1").status()).isEqualTo("ONLINE");
        assertThat(gateway.resultOf("task-1").summary()).isEqualTo("/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store test 通过");
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ExecutionGatewayTest test
```

预期：失败，原因是执行网关类型不存在。

- [x] **步骤 3：实现执行代理领域类型和服务**

创建 `AgentHeartbeat.java`：

```java
package com.matrixcode.execution.domain;

public record AgentHeartbeat(
        String agentId,
        String projectId,
        String userId,
        String status
) {
}
```

创建 `ExecutionResult.java`：

```java
package com.matrixcode.execution.domain;

public record ExecutionResult(
        String taskId,
        String agentId,
        String status,
        String summary
) {
}
```

创建 `ExecutionGateway.java`：

```java
package com.matrixcode.execution.application;

import com.matrixcode.execution.domain.AgentHeartbeat;
import com.matrixcode.execution.domain.ExecutionResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExecutionGateway {

    private final Map<String, AgentHeartbeat> heartbeats = new ConcurrentHashMap<>();
    private final Map<String, ExecutionResult> results = new ConcurrentHashMap<>();

    public void heartbeat(AgentHeartbeat heartbeat) {
        heartbeats.put(heartbeat.agentId(), heartbeat);
    }

    public AgentHeartbeat lastHeartbeat(String agentId) {
        return heartbeats.get(agentId);
    }

    public void report(ExecutionResult result) {
        results.put(result.taskId(), result);
    }

    public ExecutionResult resultOf(String taskId) {
        return results.get(taskId);
    }
}
```

- [x] **步骤 4：实现 REST 接口**

创建 `ExecutionAgentController.java`：

```java
package com.matrixcode.execution.api;

import com.matrixcode.execution.application.ExecutionGateway;
import com.matrixcode.execution.domain.AgentHeartbeat;
import com.matrixcode.execution.domain.ExecutionResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/execution-agents")
public class ExecutionAgentController {

    private final ExecutionGateway gateway;

    public ExecutionAgentController(ExecutionGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody AgentHeartbeat heartbeat) {
        gateway.heartbeat(heartbeat);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/results")
    public ResponseEntity<Void> report(@RequestBody ExecutionResult result) {
        gateway.report(result);
        return ResponseEntity.accepted().build();
    }
}
```

- [x] **步骤 5：运行测试并提交**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ExecutionGatewayTest test
git add server/src/main/java/com/matrixcode/execution server/src/test/java/com/matrixcode/execution
git commit -m "feat: 实现执行代理上报接口"
```

预期：测试通过。

---

## 任务 9：补充服务端纵切 API 控制器

**文件：**

- 创建：`server/src/test/java/com/matrixcode/api/ProjectOverviewControllerTest.java`
- 创建：`server/src/main/java/com/matrixcode/api/ProjectOverview.java`
- 创建：`server/src/main/java/com/matrixcode/api/ProjectOverviewController.java`

- [x] **步骤 1：写失败的项目概览接口测试**

创建 `ProjectOverviewControllerTest.java`：

```java
package com.matrixcode.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectOverviewController.class)
class ProjectOverviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 返回桌面端首屏需要的项目概览() throws Exception {
        mockMvc.perform(get("/api/projects/demo/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("demo"))
                .andExpect(jsonPath("$.roles[0]").value("产品"))
                .andExpect(jsonPath("$.cacheHitRate").value(0.86));
    }
}
```

- [x] **步骤 2：运行测试并确认失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ProjectOverviewControllerTest test
```

预期：失败，原因是控制器不存在。

- [x] **步骤 3：实现项目概览 DTO 和控制器**

创建 `ProjectOverview.java`：

```java
package com.matrixcode.api;

import java.util.List;

public record ProjectOverview(
        String projectId,
        String projectName,
        List<String> roles,
        List<String> stages,
        double cacheHitRate,
        long sessionTokens,
        String currentStage
) {
}
```

创建 `ProjectOverviewController.java`：

```java
package com.matrixcode.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectOverviewController {

    @GetMapping("/{projectId}/overview")
    public ProjectOverview overview(@PathVariable String projectId) {
        return new ProjectOverview(
                projectId,
                "支付系统重构",
                List.of("产品", "开发", "测试", "运维"),
                List.of("需求", "开发", "部署文档", "测试环境", "测试", "上线"),
                0.86,
                221_000,
                "测试执行"
        );
    }
}
```

- [x] **步骤 4：运行测试并提交**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ProjectOverviewControllerTest test
git add server/src/main/java/com/matrixcode/api server/src/test/java/com/matrixcode/api
git commit -m "feat: 提供项目概览接口"
```

预期：测试通过。

---

## 任务 10：创建桌面工作台壳

**文件：**

- 创建：`desktop/package.json`
- 创建：`desktop/index.html`
- 创建：`desktop/tsconfig.json`
- 创建：`desktop/vite.config.ts`
- 创建：`desktop/src/api/client.ts`
- 创建：`desktop/src/App.tsx`
- 创建：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：创建桌面端包配置**

创建 `desktop/package.json`：

```json
{
  "name": "matrixcode-desktop",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "test": "vitest run",
    "build": "tsc --noEmit && vite build"
  },
  "dependencies": {
    "@vitejs/plugin-react": "6.0.3",
    "vite": "8.1.0",
    "typescript": "6.0.3",
    "react": "19.2.7",
    "react-dom": "19.2.7",
    "@tauri-apps/cli": "2.11.3"
  },
  "devDependencies": {
    "vitest": "4.1.9",
    "@testing-library/react": "16.3.2",
    "jsdom": "29.1.1"
  }
}
```

创建 `desktop/tsconfig.json`：

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "useDefineForClassFields": true,
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "allowJs": false,
    "skipLibCheck": true,
    "esModuleInterop": true,
    "allowSyntheticDefaultImports": true,
    "strict": true,
    "forceConsistentCasingInFileNames": true,
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx"
  },
  "include": ["src"]
}
```

创建 `desktop/vite.config.ts`：

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom'
  }
});
```

- [x] **步骤 2：写失败的桌面首屏测试**

创建 `desktop/src/test/App.test.tsx`：

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import App from '../App';

describe('MatrixCode 桌面工作台', () => {
  it('展示项目、角色和缓存指标', async () => {
    render(<App />);

    expect(await screen.findByText('支付系统重构')).toBeTruthy();
    expect(await screen.findByText('产品')).toBeTruthy();
    expect(await screen.findByText('缓存命中率 86%')).toBeTruthy();
  });
});
```

- [x] **步骤 3：运行测试并确认失败**

```bash
cd desktop
npm install
npm test
```

预期：失败，原因是 `App` 不存在。

- [x] **步骤 4：实现桌面端 API 客户端和首屏**

创建 `desktop/index.html`：

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>MatrixCode</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/App.tsx"></script>
  </body>
</html>
```

创建 `desktop/src/api/client.ts`：

```typescript
export type ProjectOverview = {
  projectId: string;
  projectName: string;
  roles: string[];
  stages: string[];
  cacheHitRate: number;
  sessionTokens: number;
  currentStage: string;
};

export async function loadProjectOverview(): Promise<ProjectOverview> {
  return {
    projectId: 'demo',
    projectName: '支付系统重构',
    roles: ['产品', '开发', '测试', '运维'],
    stages: ['需求', '开发', '部署文档', '测试环境', '测试', '上线'],
    cacheHitRate: 0.86,
    sessionTokens: 221000,
    currentStage: '测试执行'
  };
}
```

创建 `desktop/src/App.tsx`：

```tsx
import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { loadProjectOverview, type ProjectOverview } from './api/client';

function App() {
  const [overview, setOverview] = useState<ProjectOverview | null>(null);

  useEffect(() => {
    loadProjectOverview().then(setOverview);
  }, []);

  if (!overview) {
    return <main style={{ padding: 24 }}>正在连接团队服务器...</main>;
  }

  return (
    <main style={{
      minHeight: '100vh',
      background: '#0b0d10',
      color: '#e6e8eb',
      fontFamily: 'Inter, system-ui, sans-serif',
      display: 'grid',
      gridTemplateColumns: '240px 1fr 280px'
    }}>
      <aside style={{ padding: 20, borderRight: '1px solid #2b2f38' }}>
        <h1 style={{ fontSize: 18 }}>MatrixCode</h1>
        <strong>{overview.projectName}</strong>
        <div style={{ marginTop: 20 }}>
          {overview.roles.map(role => <p key={role}>{role}</p>)}
        </div>
      </aside>
      <section style={{ padding: 24 }}>
        <h2>{overview.currentStage}</h2>
        <div style={{ display: 'flex', gap: 8 }}>
          {overview.stages.map(stage => (
            <span key={stage} style={{ border: '1px solid #2b2f38', padding: '8px 10px', borderRadius: 6 }}>
              {stage}
            </span>
          ))}
        </div>
      </section>
      <aside style={{ padding: 20, borderLeft: '1px solid #2b2f38' }}>
        <h2 style={{ fontSize: 14 }}>运行指标</h2>
        <p>缓存命中率 {Math.round(overview.cacheHitRate * 100)}%</p>
        <p>会话 token {overview.sessionTokens.toLocaleString('zh-CN')}</p>
      </aside>
    </main>
  );
}

export default App;

const root = document.getElementById('root');
if (root) {
  createRoot(root).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>
  );
}
```

- [x] **步骤 5：运行桌面测试和构建**

```bash
cd desktop
npm test
npm run build
```

预期：测试和构建通过。

- [x] **步骤 6：提交**

```bash
git add desktop
git commit -m "feat: 创建桌面工作台壳"
```

---

## 任务 11：补充 Docker Compose 和本地运行文档

**文件：**

- 创建：`docker-compose.yml`
- 创建：`docs/development/local-run.md`

- [x] **步骤 1：创建 Docker Compose 配置**

创建 `docker-compose.yml`：

```yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: matrixcode
      POSTGRES_USER: matrixcode
      POSTGRES_PASSWORD: matrixcode
    ports:
      - "5432:5432"
    volumes:
      - matrixcode-postgres:/var/lib/postgresql/data

  redis:
    image: redis:8
    ports:
      - "6379:6379"

volumes:
  matrixcode-postgres:
```

- [x] **步骤 2：创建中文本地运行文档**

创建 `docs/development/local-run.md`：

````markdown
# 本地运行指南

## 环境要求

- Java 21
- Maven 3.9+
- Node.js 22+
- Docker Desktop

## 启动依赖

```bash
docker compose up -d
```

## 运行服务端测试

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

## 启动服务端

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

服务端默认地址为 `http://localhost:8080`。

## 运行桌面端

```bash
cd desktop
npm install
npm run dev
```
````

- [x] **步骤 3：验证配置和文档**

运行：

```bash
docker compose config
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
cd desktop && npm test
```

预期：`docker compose config` 输出规范化配置；服务端测试通过；桌面测试通过。

验证记录：当前环境未安装 Docker CLI，`docker compose config` 无法执行；已使用 Ruby YAML 解析校验 `docker-compose.yml` 结构，服务端测试和桌面端测试、构建已通过。

- [x] **步骤 4：提交**

```bash
git add docker-compose.yml docs/development/local-run.md
git commit -m "docs: 添加本地运行指南"
```

---

## 任务 12：整体验证和收尾

**文件：**

- 修改：`docs/superpowers/plans/2026-06-23-matrixcode-mvp-vertical-slice.md`

- [x] **步骤 1：运行完整验证命令**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
cd desktop && npm test && npm run build
docker compose config
git status --short
```

预期：

- 服务端测试全部通过。
- 桌面端测试和构建通过。
- Docker Compose 配置校验通过。
- `git status --short` 没有未提交的源码变更。

验证记录：服务端测试、桌面端测试和桌面端构建已通过；`docker compose config` 因当前环境缺少 Docker CLI 无法执行；`git status --short` 仅显示既有未跟踪目录 `bugs/`。

最终审查修复记录：

- 桌面端项目概览已从静态桩数据改为请求 `GET /api/projects/{projectId}/overview`，默认服务地址为 `http://localhost:8080`，可通过 `VITE_MATRIXCODE_SERVER_URL` 覆盖。
- 实时事件已补充 `GET /api/projects/{projectId}/events/stream` 服务器发送事件接口，并在事件总线中增加订阅和取消订阅能力。
- 审批策略只自动放行带 `/Users/Masons/Ai/Maven/bin/mvn` 和 `-Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store` 的本地 Maven 测试命令；未指定本地 Maven 路径、临时 Maven 和缺少本地仓库参数的命令都需要人工确认。
- 桌面端已补充最小 Tauri 壳，提供 `npm run tauri:dev` 和 `npm run tauri:build` 入口。
- 文档中的 Maven 命令已统一为用户指定的本地 Maven 路径和仓库参数。
- 2026-06-24 复验通过：服务端全量测试、桌面端测试、桌面端构建、Tauri 构建命令帮助、本地服务启动、项目概览 HTTP 接口和健康检查。

- [x] **步骤 2：检查中文文档规范**

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|place(holder)|S[u]mmary|G[o]als|Acceptance C[r]iteria" docs || true
```

预期：没有输出。

- [x] **步骤 3：提交计划执行状态**

如果执行过程中勾选了本计划中的复选框，提交更新后的计划：

```bash
git add docs/superpowers/plans/2026-06-23-matrixcode-mvp-vertical-slice.md
git commit -m "docs: 更新 MVP 纵切实施计划状态"
```

---

## 自检记录

- 规格覆盖：本计划覆盖设计规格中的第一条可运行纵切，包括团队服务器、文档冻结、工作流、审批、上下文、用量、实时事件、执行代理接口、桌面工作台壳和本地运行说明。
- 范围控制：真实模型调用、真实文件编辑、SSH、Docker Compose 远程执行、登录鉴权、生产持久化已拆出，不混入本计划。
- 类型一致性：服务端包名统一为 `com.matrixcode`；桌面端项目名统一为 `matrixcode-desktop`；项目概览字段使用 `projectId`、`projectName`、`roles`、`stages`、`cacheHitRate`、`sessionTokens`、`currentStage`。
- 文档语言：计划正文、说明、备注均使用中文；技术名词保留行业常用英文。
