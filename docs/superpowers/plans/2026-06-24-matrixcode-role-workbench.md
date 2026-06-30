# MatrixCode 第二阶段角色工作台实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框语法跟踪进度，已完成步骤标记为 `- [x]`。

**目标：** 把第一阶段项目概览升级为可操作的四角色工作台，让产品、开发、测试、运维都能在平台内产出、冻结、交接和同步状态。

**架构：** 服务端继续使用 Java 21 + Spring Boot 模块化单体，以内存服务实现 `agent`、`bug`、`deployment`、`workbench` 四个新边界，并复用已有 `document`、`realtime`、`usage`、`context` 模块。桌面端在现有 React + TypeScript 工作台上扩展 API 客户端和四角色表单，所有动作提交后重新读取工作台聚合视图并更新右侧事件、文档和指标。

**技术栈：** Java 21、Spring Boot 3.5.15、JUnit 5、MockMvc、React 19.2.7、TypeScript 6.0.3、Vitest 4.1.9、Testing Library、Vite 8.1.0、Tauri 2。

---

## 范围检查

第二阶段只实现“平台内工作动作闭环”。真实模型供应商、真实文件编辑、真实命令执行、真实 SSH、登录鉴权和 PostgreSQL 持久化不进入本计划。所有会看起来像远程执行的字段都仅作为审计文本保存，不触发网络连接或本地文件访问。

## 文件结构

```text
server/src/main/java/com/matrixcode/
├── agent/
│   ├── application/LocalProductDraftAgent.java
│   └── domain/ProductDraftRequest.java, ProductDraftResult.java
├── bug/
│   ├── application/BugService.java
│   └── domain/ProjectBug.java, BugSeverity.java, BugStatus.java
├── deployment/
│   ├── application/DeploymentTargetService.java
│   └── domain/DeploymentTarget.java, DeploymentTargetStatus.java
├── document/
│   ├── application/DocumentService.java
│   └── domain/DocumentType.java, DocumentState.java, DocumentVersion.java
├── workbench/
│   ├── api/WorkbenchController.java
│   ├── application/WorkbenchService.java
│   └── domain/ProjectWorkbench.java, RoleSummary.java, WorkbenchMetrics.java
└── api/ProjectOverviewController.java

server/src/test/java/com/matrixcode/
├── agent/ProductDraftAgentTest.java
├── bug/BugServiceTest.java
├── deployment/DeploymentTargetServiceTest.java
├── workbench/WorkbenchServiceTest.java
└── workbench/WorkbenchControllerTest.java

desktop/src/
├── api/client.ts
├── api/client.test.ts
├── App.tsx
├── App.css
├── components/RoleSwitcher.tsx
├── components/ProductPanel.tsx
├── components/DeveloperPanel.tsx
├── components/TesterPanel.tsx
├── components/OpsPanel.tsx
├── components/InspectorPanel.tsx
└── test/App.test.tsx
```

---

### 任务 1：扩展文档模型并实现产品草稿生成器

**文件：**

- 修改：`server/src/main/java/com/matrixcode/document/domain/DocumentType.java`
- 修改：`server/src/main/java/com/matrixcode/document/application/DocumentService.java`
- 创建：`server/src/main/java/com/matrixcode/agent/domain/ProductDraftRequest.java`
- 创建：`server/src/main/java/com/matrixcode/agent/domain/ProductDraftResult.java`
- 创建：`server/src/main/java/com/matrixcode/agent/application/LocalProductDraftAgent.java`
- 测试：`server/src/test/java/com/matrixcode/agent/ProductDraftAgentTest.java`
- 测试：`server/src/test/java/com/matrixcode/document/DocumentServiceTest.java`

- [x] **步骤 1：编写产品草稿生成器失败测试**

创建 `server/src/test/java/com/matrixcode/agent/ProductDraftAgentTest.java`：

```java
package com.matrixcode.agent;

import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.agent.domain.ProductDraftRequest;
import com.matrixcode.document.domain.DocumentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductDraftAgentTest {

    private final LocalProductDraftAgent agent = new LocalProductDraftAgent();

    @Test
    void 根据产品输入生成稳定的三份中文草稿() {
        var result = agent.generate(new ProductDraftRequest(
                "支付失败后允许用户重新发起支付，并展示失败原因、订单状态和客服入口。"
        ));

        assertThat(result.documents()).hasSize(3);
        assertThat(result.documents()).extracting("type")
                .containsExactly(DocumentType.PRD, DocumentType.ACCEPTANCE_CRITERIA, DocumentType.UI_BRIEF);
        assertThat(result.documents().getFirst().title()).isEqualTo("产品需求草稿");
        assertThat(result.documents().getFirst().content())
                .contains("草稿")
                .contains("支付失败后允许用户重新发起支付")
                .contains("业务目标")
                .contains("范围边界");
        assertThat(agent.generate(new ProductDraftRequest("支付失败后允许用户重新发起支付，并展示失败原因、订单状态和客服入口。")))
                .isEqualTo(result);
    }

    @Test
    void 非支付输入不会混入支付失败场景结论() {
        var requirement = "库存低于安全水位时提醒仓库管理员补货。";

        var result = agent.generate(new ProductDraftRequest(requirement));
        var combinedContent = result.documents().stream()
                .map(document -> document.title() + "\n" + document.content())
                .reduce("", String::concat);

        assertThat(result.documents()).hasSize(3);
        assertThat(result.documents()).extracting("title")
                .containsExactly("产品需求草稿", "验收标准草稿", "初始界面说明草稿");
        assertThat(combinedContent)
                .contains(requirement)
                .doesNotContain("支付系统")
                .doesNotContain("支付失败")
                .doesNotContain("重新支付")
                .doesNotContain("客服入口")
                .doesNotContain("订单状态")
                .doesNotContain("支付失败页");
    }

    @Test
    void 空产品输入会被拒绝() {
        assertThatThrownBy(() -> agent.generate(new ProductDraftRequest(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("产品需求不能为空");
    }
}
```

- [x] **步骤 2：编写文档列表失败测试**

在 `server/src/test/java/com/matrixcode/document/DocumentServiceTest.java` 增加：

```java
@Test
void 可以按项目查询文档且返回副本() {
    service.createDraft("project-1", DocumentType.PRD, "PRD", "需求");
    service.createDraft("project-2", DocumentType.UI_BRIEF, "界面", "说明");

    var documents = service.listByProject("project-1");

    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().title()).isEqualTo("PRD");
    assertThatThrownBy(documents::clear)
            .isInstanceOf(UnsupportedOperationException.class);
}

@Test
void 按项目查询文档时按标题排序() {
    service.createDraft("project-1", DocumentType.PRD, "B 文档", "需求");
    service.createDraft("project-1", DocumentType.UI_BRIEF, "A 文档", "说明");

    var documents = service.listByProject("project-1");

    assertThat(documents).extracting("title")
            .containsExactly("A 文档", "B 文档");
}
```

- [x] **步骤 3：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ProductDraftAgentTest,DocumentServiceTest test
```

预期：编译失败，错误包含 `LocalProductDraftAgent`、`ProductDraftRequest`、`ACCEPTANCE_CRITERIA` 或 `listByProject` 不存在。

- [x] **步骤 4：实现文档类型和文档查询**

修改 `DocumentType.java`：

```java
public enum DocumentType {
    PRD,
    ACCEPTANCE_CRITERIA,
    UI_BRIEF,
    IMPLEMENTATION_NOTE,
    API_DOC,
    DATABASE_SCRIPT,
    DEPLOYMENT_DOC,
    QA_REPORT,
    ACCEPTANCE_RECORD,
    DEPLOYMENT_RECORD
}
```

在 `DocumentService.java` 增加 `@Service` 注解、输入校验和项目查询：

```java
@Service
public class DocumentService {
    public DocumentVersion createDraft(String projectId, DocumentType type, String title, String content) {
        requireNotBlank(projectId, "项目编号不能为空");
        if (type == null) {
            throw new IllegalArgumentException("文档类型不能为空");
        }
        requireNotBlank(title, "文档标题不能为空");
        requireNotBlank(content, "文档正文不能为空");
        synchronized (lock) {
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
    }

    public List<DocumentVersion> listByProject(String projectId) {
        requireNotBlank(projectId, "项目编号不能为空");
        synchronized (lock) {
            return documents.values().stream()
                    .filter(document -> projectId.equals(document.projectId()))
                    .sorted(Comparator.comparing(DocumentVersion::title).thenComparing(DocumentVersion::id))
                    .toList();
        }
    }

    private void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
```

- [x] **步骤 5：实现本地产品草稿生成器**

创建 `ProductDraftRequest.java`：

```java
package com.matrixcode.agent.domain;

public record ProductDraftRequest(String requirement) {
    public ProductDraftRequest {
        if (requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("产品需求不能为空");
        }
        requirement = requirement.trim();
    }
}
```

创建 `ProductDraftResult.java`：

```java
package com.matrixcode.agent.domain;

import com.matrixcode.document.domain.DocumentType;

import java.util.List;

public record ProductDraftResult(List<DraftDocument> documents) {
    public ProductDraftResult {
        documents = List.copyOf(documents);
    }

    public record DraftDocument(DocumentType type, String title, String content) {
    }
}
```

创建 `LocalProductDraftAgent.java`：

```java
package com.matrixcode.agent.application;

import com.matrixcode.agent.domain.ProductDraftRequest;
import com.matrixcode.agent.domain.ProductDraftResult;
import com.matrixcode.agent.domain.ProductDraftResult.DraftDocument;
import com.matrixcode.document.domain.DocumentType;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LocalProductDraftAgent {

    public ProductDraftResult generate(ProductDraftRequest request) {
        var requirement = request.requirement();
        return new ProductDraftResult(List.of(
                new DraftDocument(DocumentType.PRD, "产品需求草稿", prdContent(requirement)),
                new DraftDocument(DocumentType.ACCEPTANCE_CRITERIA, "验收标准草稿", acceptanceContent(requirement)),
                new DraftDocument(DocumentType.UI_BRIEF, "初始界面说明草稿", uiBriefContent(requirement))
        ));
    }

    private String prdContent(String requirement) {
        return """
                # 产品需求草稿

                原始产品输入：
                %s

                业务目标待确认：
                - 明确该需求希望改善的用户体验、业务效率或运营质量。
                - 识别核心使用对象、触发条件和期望结果。
                - 为产品、设计、研发和测试提供一致的草稿基线。

                范围边界：
                - 本草稿仅根据当前输入梳理初始需求，不替代正式评审结论。
                - 需要继续确认包含的用户场景、数据来源、规则口径和异常处理。
                - 暂不承诺未在输入中出现的业务流程、外部系统或运营动作。

                待确认问题：
                - 需求触发后由谁接收、处理或确认结果？
                - 成功、失败、超时和重复触发时分别如何处理？
                - 是否需要记录操作日志、通知历史或后续追踪信息？
                """.formatted(requirement);
    }

    private String acceptanceContent(String requirement) {
        return """
                # 验收标准草稿

                原始产品输入：
                %s

                验收关注点：
                - 用户在满足触发条件时可以看到或收到明确、可理解的结果。
                - 系统只在规则命中的情况下执行对应动作，并避免重复干扰。
                - 关键信息需要可追踪，便于后续排查和评估。
                - 重复生成同一输入时，草稿标题、类型和内容保持稳定。

                待确认问题：
                - 触发条件的阈值、时间窗口和数据刷新频率是什么？
                - 哪些角色可以查看、处理或关闭该事项？
                - 是否需要配置开关、权限控制或通知频率限制？
                """.formatted(requirement);
    }

    private String uiBriefContent(String requirement) {
        return """
                # 初始界面说明草稿

                原始产品输入：
                %s

                界面状态：
                - 初始状态展示需求相关的核心对象、当前状态和下一步动作。
                - 触发状态突出变化原因、影响范围和建议处理方式。
                - 空状态、加载状态和异常状态需要有明确提示，避免用户误判。

                待确认问题：
                - 该能力入口位于哪个页面、模块或工作台？
                - 用户完成主要动作后需要看到什么反馈？
                - 是否需要列表、详情、弹窗、通知或批量处理等交互形态？
                """.formatted(requirement);
    }
}
```

- [x] **步骤 6：运行测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=ProductDraftAgentTest,DocumentServiceTest test
```

预期：`ProductDraftAgentTest` 和 `DocumentServiceTest` 通过。

- [x] **步骤 7：提交**

```bash
git add server/src/main/java/com/matrixcode/agent server/src/main/java/com/matrixcode/document server/src/test/java/com/matrixcode/agent server/src/test/java/com/matrixcode/document/DocumentServiceTest.java
git commit -m "feat: 添加产品草稿生成器"
```

---

### 任务 2：实现 Bug 和部署目标领域服务

**文件：**

- 创建：`server/src/main/java/com/matrixcode/bug/domain/BugSeverity.java`
- 创建：`server/src/main/java/com/matrixcode/bug/domain/BugStatus.java`
- 创建：`server/src/main/java/com/matrixcode/bug/domain/ProjectBug.java`
- 创建：`server/src/main/java/com/matrixcode/bug/application/BugService.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/DeploymentTargetStatus.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/domain/DeploymentTarget.java`
- 创建：`server/src/main/java/com/matrixcode/deployment/application/DeploymentTargetService.java`
- 测试：`server/src/test/java/com/matrixcode/bug/BugServiceTest.java`
- 测试：`server/src/test/java/com/matrixcode/deployment/DeploymentTargetServiceTest.java`

- [x] **步骤 1：编写 Bug 服务失败测试**

创建 `BugServiceTest.java`：

```java
package com.matrixcode.bug;

import com.matrixcode.bug.application.BugService;
import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.bug.domain.BugStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BugServiceTest {

    private final BugService service = new BugService();

    @Test
    void 创建Bug后默认为新建状态并按项目查询() {
        var bug = service.create("project-1", "支付失败未提示原因", BugSeverity.HIGH,
                "提交支付", "展示失败原因", "只返回空白页", "测试", "开发");

        assertThat(bug.status()).isEqualTo(BugStatus.NEW);
        assertThat(service.listByProject("project-1")).containsExactly(bug);
        assertThat(service.listByProject("project-2")).isEmpty();
    }

    @Test
    void Bug可以按合法路径流转() {
        var bug = service.create("project-1", "支付失败未提示原因", BugSeverity.HIGH,
                "提交支付", "展示失败原因", "只返回空白页", "测试", "开发");

        var confirmed = service.transition(bug.id(), BugStatus.CONFIRMED, "测试确认");
        var fixing = service.transition(confirmed.id(), BugStatus.FIXING, "开发处理中");
        var regression = service.transition(fixing.id(), BugStatus.REGRESSION_PENDING, "等待回归");
        var closed = service.transition(regression.id(), BugStatus.CLOSED, "回归通过");

        assertThat(closed.status()).isEqualTo(BugStatus.CLOSED);
        assertThat(closed.lastNote()).isEqualTo("回归通过");
    }

    @Test
    void 已关闭Bug可以重新打开但不能直接进入修复中() {
        var bug = service.create("project-1", "支付失败未提示原因", BugSeverity.HIGH,
                "提交支付", "展示失败原因", "只返回空白页", "测试", "开发");

        assertThatThrownBy(() -> service.transition(bug.id(), BugStatus.FIXING, "跳过确认"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法 Bug 状态流转");
    }
}
```

- [x] **步骤 2：编写部署目标失败测试**

创建 `DeploymentTargetServiceTest.java`：

```java
package com.matrixcode.deployment;

import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.deployment.domain.DeploymentTargetStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentTargetServiceTest {

    private final DeploymentTargetService service = new DeploymentTargetService();

    @Test
    void 配置部署目标时只保存配置不执行远程连接() {
        var target = service.configure("project-1", "测试环境", "https://test.example.com",
                "deploy@example.com", "使用 Docker Compose 部署", "https://test.example.com/health", "保留上一版本镜像");

        assertThat(target.status()).isEqualTo(DeploymentTargetStatus.RECORDED);
        assertThat(target.sshAddress()).isEqualTo("deploy@example.com");
        assertThat(target.remoteExecuted()).isFalse();
        assertThat(service.listByProject("project-1")).containsExactly(target);
    }

    @Test
    void 空环境名称会被拒绝() {
        assertThatThrownBy(() -> service.configure("project-1", " ", "https://test.example.com",
                "deploy@example.com", "部署", "https://test.example.com/health", "回滚"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("环境名称不能为空");
    }
}
```

- [x] **步骤 3：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=BugServiceTest,DeploymentTargetServiceTest test
```

预期：编译失败，错误包含 `BugService` 和 `DeploymentTargetService` 不存在。

- [x] **步骤 4：实现 Bug 领域服务**

创建枚举：

```java
public enum BugSeverity {
    LOW, MEDIUM, HIGH, BLOCKER
}

public enum BugStatus {
    NEW, CONFIRMED, FIXING, REGRESSION_PENDING, CLOSED, REOPENED
}
```

创建 `ProjectBug`：

```java
public record ProjectBug(
        String id,
        String projectId,
        String title,
        BugSeverity severity,
        BugStatus status,
        String steps,
        String expected,
        String actual,
        String createdByRole,
        String currentOwnerRole,
        String lastNote,
        Instant updatedAt
) {
    public ProjectBug withStatus(BugStatus nextStatus, String note, Instant now) {
        return new ProjectBug(id, projectId, title, severity, nextStatus, steps, expected, actual,
                createdByRole, currentOwnerRole, note, now);
    }
}
```

创建 `BugService`，使用 `ConcurrentHashMap<String, ProjectBug>` 保存数据，`create` 生成 `UUID`，`transition` 使用如下规则：

```java
private boolean canMove(BugStatus current, BugStatus next) {
    return switch (current) {
        case NEW -> next == BugStatus.CONFIRMED || next == BugStatus.CLOSED;
        case CONFIRMED -> next == BugStatus.FIXING || next == BugStatus.CLOSED;
        case FIXING -> next == BugStatus.REGRESSION_PENDING;
        case REGRESSION_PENDING -> next == BugStatus.CLOSED || next == BugStatus.REOPENED;
        case REOPENED -> next == BugStatus.FIXING || next == BugStatus.CLOSED;
        case CLOSED -> next == BugStatus.REOPENED;
    };
}
```

- [x] **步骤 5：实现部署目标服务**

创建枚举：

```java
public enum DeploymentTargetStatus {
    NOT_CONFIGURED, APPROVAL_PENDING, RECORDED, RELEASE_READY, DEPLOYED
}
```

创建 `DeploymentTarget`：

```java
public record DeploymentTarget(
        String id,
        String projectId,
        String environmentName,
        String environmentUrl,
        String sshAddress,
        String deployNote,
        String healthCheckUrl,
        String rollbackNote,
        DeploymentTargetStatus status,
        boolean remoteExecuted,
        Instant updatedAt
) {
}
```

创建 `DeploymentTargetService`，`configure` 只保存字段并设置 `remoteExecuted=false`、`status=RECORDED`，不得调用任何网络或命令执行 API。

- [x] **步骤 6：运行测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=BugServiceTest,DeploymentTargetServiceTest test
```

预期：两个测试类通过。

- [x] **步骤 7：提交**

```bash
git add server/src/main/java/com/matrixcode/bug server/src/main/java/com/matrixcode/deployment server/src/test/java/com/matrixcode/bug server/src/test/java/com/matrixcode/deployment
git commit -m "feat: 添加缺陷和部署目标服务"
```

---

### 任务 3：实现项目工作台聚合服务

**文件：**

- 创建：`server/src/main/java/com/matrixcode/workbench/domain/RoleSummary.java`
- 创建：`server/src/main/java/com/matrixcode/workbench/domain/WorkbenchMetrics.java`
- 创建：`server/src/main/java/com/matrixcode/workbench/domain/DocumentSummary.java`
- 创建：`server/src/main/java/com/matrixcode/workbench/domain/ProjectWorkbench.java`
- 创建：`server/src/main/java/com/matrixcode/workbench/application/WorkbenchService.java`
- 测试：`server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java`

- [x] **步骤 1：编写工作台服务失败测试**

创建 `WorkbenchServiceTest.java`：

```java
package com.matrixcode.workbench;

import com.matrixcode.agent.application.LocalProductDraftAgent;
import com.matrixcode.bug.application.BugService;
import com.matrixcode.bug.domain.BugSeverity;
import com.matrixcode.deployment.application.DeploymentTargetService;
import com.matrixcode.document.application.DocumentService;
import com.matrixcode.document.domain.DocumentState;
import com.matrixcode.realtime.application.ProjectEventBus;
import com.matrixcode.workbench.application.WorkbenchService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchServiceTest {

    private final ProjectEventBus events = new ProjectEventBus();
    private final WorkbenchService service = new WorkbenchService(
            new DocumentService(),
            new LocalProductDraftAgent(),
            new BugService(),
            new DeploymentTargetService(),
            events
    );

    @Test
    void 产品生成草稿后工作台出现三份文档和事件() {
        var drafts = service.createProductDrafts("demo", "支付失败后允许用户重新发起支付。");

        var workbench = service.get("demo");

        assertThat(drafts).hasSize(3);
        assertThat(workbench.currentStage()).isEqualTo("需求草稿");
        assertThat(workbench.documents()).hasSize(3);
        assertThat(workbench.events()).extracting("type").contains("PRODUCT_DRAFT_CREATED");
    }

    @Test
    void 冻结需求后开发角色可以看到冻结文档且阶段进入开发中() {
        var drafts = service.createProductDrafts("demo", "支付失败后允许用户重新发起支付。");

        service.freezeDocument("demo", drafts.getFirst().id(), "user-product");

        var workbench = service.get("demo");
        assertThat(workbench.currentStage()).isEqualTo("开发中");
        assertThat(workbench.documents().getFirst().state()).isEqualTo(DocumentState.FROZEN);
        assertThat(workbench.roles()).anySatisfy(role -> {
            assertThat(role.name()).isEqualTo("开发");
            assertThat(role.state()).isEqualTo("可开始实现");
        });
    }

    @Test
    void 开发交付测试Bug部署目标会共同更新工作台投影() {
        var drafts = service.createProductDrafts("demo", "支付失败后允许用户重新发起支付。");
        service.freezeDocument("demo", drafts.getFirst().id(), "user-product");
        service.submitDeveloperDelivery("demo", "/repo/payment", "完成失败态处理", "测试通过",
                "GET /payments/{id}", "alter table payment add fail_reason varchar(255);", "docker compose up -d");
        var bug = service.createBug("demo", "失败原因为空", BugSeverity.HIGH,
                "支付失败", "显示失败原因", "为空白", "测试", "开发");
        service.transitionBug("demo", bug.id(), "REGRESSION_PENDING", "开发已修复");
        service.submitTestReport("demo", "核心链路通过，等待产品验收");
        service.configureDeploymentTarget("demo", "测试环境", "https://test.example.com",
                "deploy@example.com", "按部署文档执行", "https://test.example.com/health", "回滚上一版本");

        var workbench = service.get("demo");

        assertThat(workbench.documents()).extracting("title")
                .contains("实现说明", "接口文档", "数据库脚本", "部署文档", "测试报告");
        assertThat(workbench.bugs()).hasSize(1);
        assertThat(workbench.deploymentTargets()).hasSize(1);
        assertThat(workbench.metrics().eventCount()).isGreaterThanOrEqualTo(6);
    }
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchServiceTest test
```

预期：编译失败，错误包含 `WorkbenchService` 或工作台领域记录不存在。

- [x] **步骤 3：实现工作台领域记录**

创建 `RoleSummary`：

```java
public record RoleSummary(String name, String state, int todoCount, String latestAction) {
}
```

创建 `WorkbenchMetrics`：

```java
public record WorkbenchMetrics(double cacheHitRate, long sessionTokens, int eventCount, int documentCount, int openBugCount) {
}
```

创建 `DocumentSummary`：

```java
public record DocumentSummary(String id, DocumentType type, String title, String content, int version, DocumentState state) {
    public static DocumentSummary from(DocumentVersion document) {
        return new DocumentSummary(document.id(), document.type(), document.title(), document.content(), document.version(), document.state());
    }
}
```

创建 `ProjectWorkbench`，包含项目、角色、文档、Bug、部署目标、指标和事件：

```java
public record ProjectWorkbench(
        String projectId,
        String projectName,
        String currentStage,
        List<RoleSummary> roles,
        List<DocumentSummary> documents,
        List<ProjectBug> bugs,
        List<DeploymentTarget> deploymentTargets,
        WorkbenchMetrics metrics,
        List<ProjectEvent> events
) {
    public ProjectWorkbench {
        roles = List.copyOf(roles);
        documents = List.copyOf(documents);
        bugs = List.copyOf(bugs);
        deploymentTargets = List.copyOf(deploymentTargets);
        events = List.copyOf(events);
    }
}
```

- [x] **步骤 4：实现工作台服务动作方法**

`WorkbenchService` 使用构造注入，公开以下方法：

```java
public ProjectWorkbench get(String projectId)
public List<DocumentSummary> createProductDrafts(String projectId, String requirement)
public DocumentSummary freezeDocument(String projectId, String documentId, String actorId)
public List<DocumentSummary> submitDeveloperDelivery(String projectId, String workspacePath, String implementationNote,
        String selfTestResult, String apiDoc, String databaseScript, String deploymentDoc)
public ProjectBug createBug(String projectId, String title, BugSeverity severity, String steps, String expected,
        String actual, String createdByRole, String currentOwnerRole)
public ProjectBug transitionBug(String projectId, String bugId, String nextStatus, String note)
public DocumentSummary submitTestReport(String projectId, String report)
public DeploymentTarget configureDeploymentTarget(String projectId, String environmentName, String environmentUrl,
        String sshAddress, String deployNote, String healthCheckUrl, String rollbackNote)
public DocumentSummary submitAcceptance(String projectId, boolean accepted, String note)
```

每个动作完成后调用：

```java
eventBus.publish(new ProjectEvent(projectId, "PRODUCT_DRAFT_CREATED", "产品生成了需求草稿"));
```

阶段投影方法使用文档、Bug、部署目标和验收记录计算：

```java
private String currentStage(String projectId) {
    var documents = documentService.listByProject(projectId);
    var hasFrozenPrd = documents.stream().anyMatch(document -> document.type() == DocumentType.PRD && document.state() == DocumentState.FROZEN);
    var hasDeveloperDelivery = documents.stream().anyMatch(document -> document.type() == DocumentType.IMPLEMENTATION_NOTE);
    var hasTestReport = documents.stream().anyMatch(document -> document.type() == DocumentType.QA_REPORT);
    var hasAcceptance = documents.stream().anyMatch(document -> document.type() == DocumentType.ACCEPTANCE_RECORD);
    var hasDeployment = !deploymentTargetService.listByProject(projectId).isEmpty();
    var hasBlockingBug = bugService.listByProject(projectId).stream()
            .anyMatch(bug -> bug.status() != BugStatus.CLOSED && (bug.severity() == BugSeverity.HIGH || bug.severity() == BugSeverity.BLOCKER));
    if (!hasFrozenPrd) {
        return documents.isEmpty() ? "需求录入" : "需求草稿";
    }
    if (!hasDeveloperDelivery) {
        return "开发中";
    }
    if (hasBlockingBug) {
        return "缺陷处理中";
    }
    if (!hasTestReport) {
        return "测试中";
    }
    if (!hasAcceptance) {
        return "待产品验收";
    }
    return hasDeployment ? "上线准备" : "待运维配置";
}
```

- [x] **步骤 5：运行测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchServiceTest test
```

预期：`WorkbenchServiceTest` 通过。

- [x] **步骤 6：提交**

```bash
git add server/src/main/java/com/matrixcode/workbench server/src/test/java/com/matrixcode/workbench/WorkbenchServiceTest.java
git commit -m "feat: 聚合项目角色工作台"
```

---

### 任务 4：提供工作台 REST 接口并保持概览接口兼容

**文件：**

- 创建：`server/src/main/java/com/matrixcode/workbench/api/WorkbenchController.java`
- 修改：`server/src/main/java/com/matrixcode/api/ProjectOverviewController.java`
- 测试：`server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java`
- 测试：`server/src/test/java/com/matrixcode/api/ProjectOverviewControllerTest.java`

- [x] **步骤 1：编写工作台接口失败测试**

创建 `WorkbenchControllerTest.java`：

```java
package com.matrixcode.workbench;

import com.matrixcode.MatrixCodeServerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = MatrixCodeServerApplication.class)
@AutoConfigureMockMvc
class WorkbenchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 产品草稿冻结开发交付Bug和部署目标可以通过接口串起来() throws Exception {
        mockMvc.perform(post("/api/projects/demo/roles/product/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"支付失败后允许用户重新发起支付。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("产品需求草稿"));

        var workbench = mockMvc.perform(get("/api/projects/demo/workbench"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].id").isNotEmpty())
                .andReturn();
        var documentId = com.jayway.jsonpath.JsonPath.read(workbench.getResponse().getContentAsString(), "$.documents[0].id").toString();

        mockMvc.perform(post("/api/projects/demo/documents/" + documentId + "/freeze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FROZEN"));

        mockMvc.perform(post("/api/projects/demo/roles/developer/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspacePath":"/repo/payment","implementationNote":"完成失败态处理","selfTestResult":"测试通过","apiDoc":"GET /payments/{id}","databaseScript":"alter table payment add fail_reason varchar(255);","deploymentDoc":"docker compose up -d"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/projects/demo/bugs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"失败原因为空","severity":"HIGH","steps":"支付失败","expected":"显示失败原因","actual":"为空白","createdByRole":"测试","currentOwnerRole":"开发"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));

        mockMvc.perform(post("/api/projects/demo/deployments/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"environmentName":"测试环境","environmentUrl":"https://test.example.com","sshAddress":"deploy@example.com","deployNote":"按部署文档执行","healthCheckUrl":"https://test.example.com/health","rollbackNote":"回滚上一版本"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remoteExecuted").value(false));
    }
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchControllerTest test
```

预期：失败，接口控制器不存在或依赖无法注入。

- [x] **步骤 3：实现工作台控制器**

创建 `WorkbenchController`，请求体记录放在控制器内部，保持接口清晰：

```java
@RestController
@RequestMapping("/api/projects/{projectId}")
public class WorkbenchController {
    private final WorkbenchService service;

    public WorkbenchController(WorkbenchService service) {
        this.service = service;
    }

    @GetMapping("/workbench")
    public ProjectWorkbench get(@PathVariable String projectId) {
        return service.get(projectId);
    }

    @PostMapping("/roles/product/drafts")
    public List<DocumentSummary> createProductDrafts(@PathVariable String projectId, @RequestBody ProductDraftCommand command) {
        return service.createProductDrafts(projectId, command.requirement());
    }

    @PostMapping("/documents/{documentId}/freeze")
    public DocumentSummary freeze(@PathVariable String projectId, @PathVariable String documentId) {
        return service.freezeDocument(projectId, documentId, "user-product");
    }

    public record ProductDraftCommand(String requirement) {
    }
}
```

继续在 `WorkbenchController` 中加入开发交付、Bug、测试报告、部署目标和验收接口：

```java
@PostMapping("/roles/developer/deliveries")
public List<DocumentSummary> submitDeveloperDelivery(@PathVariable String projectId, @RequestBody DeveloperDeliveryCommand command) {
    return service.submitDeveloperDelivery(projectId, command.workspacePath(), command.implementationNote(),
            command.selfTestResult(), command.apiDoc(), command.databaseScript(), command.deploymentDoc());
}

@PostMapping("/bugs")
public ProjectBug createBug(@PathVariable String projectId, @RequestBody BugCommand command) {
    return service.createBug(projectId, command.title(), command.severity(), command.steps(),
            command.expected(), command.actual(), command.createdByRole(), command.currentOwnerRole());
}

@PostMapping("/bugs/{bugId}/transitions")
public ProjectBug transitionBug(@PathVariable String projectId, @PathVariable String bugId, @RequestBody BugTransitionCommand command) {
    return service.transitionBug(projectId, bugId, command.nextStatus(), command.note());
}

@PostMapping("/roles/tester/reports")
public DocumentSummary submitTestReport(@PathVariable String projectId, @RequestBody TestReportCommand command) {
    return service.submitTestReport(projectId, command.report());
}

@PostMapping("/deployments/targets")
public DeploymentTarget configureDeploymentTarget(@PathVariable String projectId, @RequestBody DeploymentTargetCommand command) {
    return service.configureDeploymentTarget(projectId, command.environmentName(), command.environmentUrl(),
            command.sshAddress(), command.deployNote(), command.healthCheckUrl(), command.rollbackNote());
}

@PostMapping("/roles/product/acceptance")
public DocumentSummary submitAcceptance(@PathVariable String projectId, @RequestBody AcceptanceCommand command) {
    return service.submitAcceptance(projectId, command.accepted(), command.note());
}

public record DeveloperDeliveryCommand(String workspacePath, String implementationNote, String selfTestResult,
                                       String apiDoc, String databaseScript, String deploymentDoc) {
}

public record BugCommand(String title, BugSeverity severity, String steps, String expected, String actual,
                         String createdByRole, String currentOwnerRole) {
}

public record BugTransitionCommand(String nextStatus, String note) {
}

public record TestReportCommand(String report) {
}

public record DeploymentTargetCommand(String environmentName, String environmentUrl, String sshAddress,
                                      String deployNote, String healthCheckUrl, String rollbackNote) {
}

public record AcceptanceCommand(boolean accepted, String note) {
}
```

- [x] **步骤 4：让项目概览读取工作台聚合阶段**

修改 `ProjectOverviewController` 构造注入 `WorkbenchService`，`overview` 从 `service.get(projectId)` 获取阶段、角色、缓存命中率和 token，保持旧接口响应字段不变。

- [x] **步骤 5：运行接口测试验证通过**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=WorkbenchControllerTest,ProjectOverviewControllerTest test
```

预期：工作台接口测试和原概览接口测试通过。

- [x] **步骤 6：提交**

```bash
git add server/src/main/java/com/matrixcode/workbench/api server/src/main/java/com/matrixcode/api/ProjectOverviewController.java server/src/test/java/com/matrixcode/workbench/WorkbenchControllerTest.java server/src/test/java/com/matrixcode/api/ProjectOverviewControllerTest.java
git commit -m "feat: 暴露角色工作台接口"
```

---

### 任务 5：扩展桌面端 API 客户端

**文件：**

- 修改：`desktop/src/api/client.ts`
- 修改：`desktop/src/api/client.test.ts`

- [x] **步骤 1：编写客户端失败测试**

在 `desktop/src/api/client.test.ts` 增加：

```ts
import {
  configureDeploymentTarget,
  createBug,
  createProductDrafts,
  freezeDocument,
  loadProjectWorkbench,
  submitDeveloperDelivery,
  transitionBug
} from './client';

it('加载角色工作台', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ projectId: 'demo', projectName: '支付系统重构', currentStage: '需求录入', roles: [], documents: [], bugs: [], deploymentTargets: [], metrics: { cacheHitRate: 0.86, sessionTokens: 221000, eventCount: 0, documentCount: 0, openBugCount: 0 }, events: [] })
  });
  vi.stubGlobal('fetch', fetchMock);

  const workbench = await loadProjectWorkbench('demo', 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/workbench', {
    headers: { Accept: 'application/json' }
  });
  expect(workbench.currentStage).toBe('需求录入');
});

it('提交角色动作时使用 JSON 请求体', async () => {
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
  vi.stubGlobal('fetch', fetchMock);

  await createProductDrafts('demo', { requirement: '支付失败后允许用户重新发起支付。' }, 'http://localhost:8080');

  expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/projects/demo/roles/product/drafts', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ requirement: '支付失败后允许用户重新发起支付。' })
  });
});
```

- [x] **步骤 2：运行测试验证失败**

```bash
cd desktop && npm test -- src/api/client.test.ts
```

预期：失败，错误包含新增导出不存在。

- [x] **步骤 3：实现客户端类型和请求工具**

在 `client.ts` 增加类型：

```ts
export type ProjectWorkbench = {
  projectId: string;
  projectName: string;
  currentStage: string;
  roles: RoleSummary[];
  documents: DocumentSummary[];
  bugs: ProjectBug[];
  deploymentTargets: DeploymentTarget[];
  metrics: WorkbenchMetrics;
  events: ProjectEvent[];
};
```

增加通用请求方法：

```ts
async function requestJson<T>(url: string, init?: RequestInit): Promise<T> {
  try {
    const response = await fetch(url, {
      ...init,
      headers: {
        Accept: 'application/json',
        ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
        ...init?.headers
      }
    });
    if (!response.ok) {
      throw new Error(`团队服务器请求失败：${response.status} ${response.statusText}`.trim());
    }
    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof Error && error.message.startsWith('团队服务器请求失败')) {
      throw error;
    }
    throw new Error('团队服务器连接失败');
  }
}
```

增加动作方法：

```ts
export function loadProjectWorkbench(projectId = 'demo', serverUrl = matrixCodeServerUrl()) {
  return requestJson<ProjectWorkbench>(joinUrl(serverUrl, `/api/projects/${encodeURIComponent(projectId)}/workbench`));
}

export function createProductDrafts(projectId: string, input: { requirement: string }, serverUrl = matrixCodeServerUrl()) {
  return requestJson<DocumentSummary[]>(joinUrl(serverUrl, `/api/projects/${encodeURIComponent(projectId)}/roles/product/drafts`), {
    method: 'POST',
    body: JSON.stringify(input)
  });
}
```

同文件继续实现 `freezeDocument`、`submitDeveloperDelivery`、`createBug`、`transitionBug`、`submitTestReport`、`configureDeploymentTarget`、`submitAcceptance`。

- [x] **步骤 4：运行客户端测试验证通过**

```bash
cd desktop && npm test -- src/api/client.test.ts
```

预期：客户端测试通过。

- [x] **步骤 5：提交**

```bash
git add desktop/src/api/client.ts desktop/src/api/client.test.ts
git commit -m "feat: 扩展角色工作台客户端接口"
```

---

### 任务 6：实现桌面端四角色工作区

**文件：**

- 修改：`desktop/src/App.tsx`
- 修改：`desktop/src/App.css`
- 创建：`desktop/src/components/RoleSwitcher.tsx`
- 创建：`desktop/src/components/ProductPanel.tsx`
- 创建：`desktop/src/components/DeveloperPanel.tsx`
- 创建：`desktop/src/components/TesterPanel.tsx`
- 创建：`desktop/src/components/OpsPanel.tsx`
- 创建：`desktop/src/components/InspectorPanel.tsx`
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写角色工作区失败测试**

替换或扩展 `desktop/src/test/App.test.tsx` 的 mock，使其模拟新客户端：

```ts
vi.mock('../api/client', () => ({
  loadProjectWorkbench: vi.fn(),
  createProductDrafts: vi.fn(),
  freezeDocument: vi.fn(),
  submitDeveloperDelivery: vi.fn(),
  createBug: vi.fn(),
  transitionBug: vi.fn(),
  submitTestReport: vi.fn(),
  configureDeploymentTarget: vi.fn(),
  submitAcceptance: vi.fn()
}));
```

增加产品流程测试：

```ts
it('产品可以输入需求生成草稿并冻结文档', async () => {
  加载工作台.mockResolvedValueOnce(空工作台).mockResolvedValueOnce(含草稿工作台).mockResolvedValueOnce(冻结后工作台);
  生成产品草稿.mockResolvedValue([]);
  冻结文档.mockResolvedValue(含草稿工作台.documents[0]);

  render(<App />);

  fireEvent.change(await screen.findByLabelText('产品需求'), {
    target: { value: '支付失败后允许用户重新发起支付。' }
  });
  fireEvent.click(screen.getByRole('button', { name: '生成需求草稿' }));

  expect(生成产品草稿).toHaveBeenCalledWith('demo', { requirement: '支付失败后允许用户重新发起支付。' });
  expect(await screen.findByText('产品需求草稿')).toBeTruthy();

  fireEvent.click(screen.getByRole('button', { name: '冻结当前 PRD' }));
  expect(冻结文档).toHaveBeenCalled();
});
```

增加角色切换测试：

```ts
it('可以切换开发测试运维工作区', async () => {
  加载工作台.mockResolvedValue(冻结后工作台);

  render(<App />);

  fireEvent.click(await screen.findByRole('button', { name: '开发' }));
  expect(screen.getByLabelText('本地工作区路径')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '测试' }));
  expect(screen.getByLabelText('Bug 标题')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '运维' }));
  expect(screen.getByLabelText('SSH 地址')).toBeTruthy();
});
```

- [x] **步骤 2：运行测试验证失败**

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

预期：失败，错误包含 `loadProjectWorkbench` mock 未被使用或角色按钮不存在。

- [x] **步骤 3：拆分角色组件**

`RoleSwitcher.tsx`：

```tsx
type RoleSwitcherProps = {
  activeRole: string;
  roles: { name: string; state: string; todoCount: number }[];
  onChange: (role: string) => void;
};

export function RoleSwitcher({ activeRole, roles, onChange }: RoleSwitcherProps) {
  return (
    <nav className="role-list" aria-label="角色工作区">
      {roles.map((role) => (
        <button className={`role-item ${role.name === activeRole ? 'role-item--active' : ''}`} key={role.name} onClick={() => onChange(role.name)} type="button">
          <span className="role-item__mark">{role.name.slice(0, 1)}</span>
          <span>
            <strong className="role-item__name">{role.name}</strong>
            <span className="role-item__state">{role.state}</span>
          </span>
          <span className="role-item__count">{role.todoCount}</span>
        </button>
      ))}
    </nav>
  );
}
```

`ProductPanel.tsx` 提供 `textarea aria-label="产品需求"`、`button` 文案 `生成需求草稿` 和 `冻结当前 PRD`。`DeveloperPanel.tsx` 提供本地工作区路径、实现说明、自测结果、接口文档、数据库脚本、部署文档。`TesterPanel.tsx` 提供 Bug 标题、严重级别、复现步骤、期望结果、实际结果、测试报告。`OpsPanel.tsx` 提供环境名称、环境地址、SSH 地址、部署说明、健康检查地址、回滚说明。

- [x] **步骤 4：改造 App 使用工作台聚合视图**

`App.tsx` 使用：

```tsx
const [workbenchState, setWorkbenchState] = useState<WorkbenchState>({ type: 'loading' });
const [activeRole, setActiveRole] = useState('产品');

async function refreshWorkbench() {
  setWorkbenchState((current) => current.type === 'ready' ? { ...current, refreshing: true } : { type: 'loading' });
  try {
    const workbench = await loadProjectWorkbench();
    setWorkbenchState({ type: 'ready', workbench, refreshing: false });
  } catch {
    setWorkbenchState({ type: 'error', message: '团队服务器暂时不可用' });
  }
}
```

每个角色动作成功后调用 `await refreshWorkbench()`。不要在前端伪造成功状态。

- [x] **步骤 5：更新样式**

`App.css` 保留三栏结构，增加表单样式：

```css
.role-form {
  display: grid;
  gap: 12px;
}

.field {
  display: grid;
  gap: 6px;
}

.field label {
  color: #aeb7c4;
  font-size: 12px;
}

.field input,
.field textarea,
.field select {
  width: 100%;
  border: 1px solid #30343b;
  border-radius: 8px;
  padding: 10px 12px;
  color: #eef2f6;
  background: #121419;
}
```

- [x] **步骤 6：运行桌面测试验证通过**

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

预期：角色切换、产品生成和冻结测试通过。

- [x] **步骤 7：提交**

```bash
git add desktop/src/App.tsx desktop/src/App.css desktop/src/components desktop/src/test/App.test.tsx
git commit -m "feat: 实现四角色工作台界面"
```

---

### 任务 7：补齐测试角色和运维角色前端流程

**文件：**

- 修改：`desktop/src/components/TesterPanel.tsx`
- 修改：`desktop/src/components/OpsPanel.tsx`
- 修改：`desktop/src/components/InspectorPanel.tsx`
- 修改：`desktop/src/test/App.test.tsx`

- [x] **步骤 1：编写测试和运维流程失败测试**

在 `App.test.tsx` 增加：

```ts
it('测试可以创建 Bug 并提交测试报告', async () => {
  加载工作台.mockResolvedValue(开发交付后工作台);
  创建Bug.mockResolvedValue(开发交付后工作台.bugs[0]);
  提交测试报告.mockResolvedValue(开发交付后工作台.documents[0]);

  render(<App />);

  fireEvent.click(await screen.findByRole('button', { name: '测试' }));
  fireEvent.change(screen.getByLabelText('Bug 标题'), { target: { value: '失败原因为空' } });
  fireEvent.click(screen.getByRole('button', { name: '记录 Bug' }));
  expect(创建Bug).toHaveBeenCalled();

  fireEvent.change(screen.getByLabelText('测试报告'), { target: { value: '核心链路通过' } });
  fireEvent.click(screen.getByRole('button', { name: '提交测试报告' }));
  expect(提交测试报告).toHaveBeenCalled();
});

it('运维可以配置环境但不会触发远程执行', async () => {
  加载工作台.mockResolvedValue(测试通过后工作台);
  配置部署目标.mockResolvedValue(测试通过后工作台.deploymentTargets[0]);

  render(<App />);

  fireEvent.click(await screen.findByRole('button', { name: '运维' }));
  fireEvent.change(screen.getByLabelText('SSH 地址'), { target: { value: 'deploy@example.com' } });
  fireEvent.click(screen.getByRole('button', { name: '保存部署目标' }));

  expect(配置部署目标).toHaveBeenCalledWith('demo', expect.objectContaining({ sshAddress: 'deploy@example.com' }));
});
```

- [x] **步骤 2：运行测试验证失败**

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

预期：失败，错误指向按钮或动作未实现。

- [x] **步骤 3：实现测试面板动作**

`TesterPanel.tsx` 提交 Bug：

```tsx
await createBug(projectId, {
  title,
  severity,
  steps,
  expected,
  actual,
  createdByRole: '测试',
  currentOwnerRole: '开发'
});
await onChanged();
```

提交测试报告：

```tsx
await submitTestReport(projectId, { report });
await onChanged();
```

- [x] **步骤 4：实现运维面板动作和右侧栏展示**

`OpsPanel.tsx` 提交部署目标：

```tsx
await configureDeploymentTarget(projectId, {
  environmentName,
  environmentUrl,
  sshAddress,
  deployNote,
  healthCheckUrl,
  rollbackNote
});
await onChanged();
```

`InspectorPanel.tsx` 展示：

```tsx
<h3>Bug 队列</h3>
<h3>部署状态</h3>
<h3>事件流</h3>
```

- [x] **步骤 5：运行桌面测试验证通过**

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

预期：测试和运维流程测试通过。

- [x] **步骤 6：提交**

```bash
git add desktop/src/components/TesterPanel.tsx desktop/src/components/OpsPanel.tsx desktop/src/components/InspectorPanel.tsx desktop/src/test/App.test.tsx
git commit -m "feat: 补齐测试和运维工作流"
```

---

### 任务 8：整体验证、运行文档和计划状态

**文件：**

- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-24-matrixcode-role-workbench.md`

- [x] **步骤 1：运行服务端全量测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
```

预期：服务端全部测试通过。

- [x] **步骤 2：运行桌面端测试和构建**

```bash
cd desktop && npm test && npm run build && npm run tauri:build -- --help
```

预期：桌面端测试、TypeScript 构建、Vite 构建和 Tauri 命令入口通过。

- [x] **步骤 3：启动服务并手工验证工作台接口**

启动服务端：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server spring-boot:run
```

另一个终端验证：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
curl -sS -X POST http://localhost:8080/api/projects/demo/roles/product/drafts \
  -H 'Content-Type: application/json' \
  -d '{"requirement":"支付失败后允许用户重新发起支付。"}'
```

预期：第一个命令返回项目工作台 JSON；第二个命令返回三份中文草稿文档。

- [x] **步骤 4：更新本地运行文档**

在 `docs/development/local-run.md` 增加第二阶段验证说明：

````markdown
## 第二阶段角色工作台验证

服务端启动后可以访问：

```bash
curl -sS http://localhost:8080/api/projects/demo/workbench
```

桌面端启动后访问 `http://127.0.0.1:5173/`，依次切换产品、开发、测试、运维角色，提交各角色表单，右侧事件流、文档交接、Bug 队列和部署状态应同步更新。产品可提交验收通过或退回开发、测试；测试可流转 Bug 状态。
````

- [x] **步骤 5：检查文档中文和 Maven 命令**

```bash
rg -n "T(O)DO|T[B]D|F[I]XME|\\bplace(holder)\\b|\\bS[u]mmary\\b|\\bG[o]als\\b|\\bAcceptance C[r]iteria\\b" docs || true
rg --pcre2 -n "(?<!bin/)\\bm[v]n\\b" docs || true
git diff --check
```

预期：前三个命令没有输出。

- [x] **步骤 6：提交计划和文档状态**

勾选本计划已完成步骤，并提交：

```bash
git add docs/development/local-run.md docs/superpowers/plans/2026-06-24-matrixcode-role-workbench.md
git commit -m "docs: 记录第二阶段角色工作台验证"
```

---

## 自检记录

- 规格覆盖：产品草稿、文档冻结、开发交付、Bug 创建与流转、测试报告、运维部署目标、产品验收、事件同步、工作台聚合视图、桌面角色切换均有对应任务。
- 范围控制：真实模型供应商、真实工作区读写、真实 SSH、登录鉴权和持久化不进入本计划。
- 类型一致性：服务端文档类型使用 `DocumentType`；Bug 状态使用 `BugStatus`；部署目标状态使用 `DeploymentTargetStatus`；桌面端聚合类型使用 `ProjectWorkbench`。
- 验证命令：服务端命令均使用 `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store`。
- 第二阶段验证结果：
  - `/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过。
  - `cd desktop && npm test` 通过，桌面端 2 个测试文件、28 个测试通过。
  - `cd desktop && npm run build && npm run tauri:build -- --help` 通过，TypeScript、Vite 构建和 Tauri 命令入口可用。
  - `/api/projects/demo/workbench` 返回项目工作台 JSON，初始阶段为 `需求录入`。
  - `/api/projects/demo/roles/product/drafts` 返回 `产品需求草稿`、`验收标准草稿`、`初始界面说明草稿` 三份中文草稿。
  - 审查修复覆盖：产品验收入口、测试 Bug 流转入口、阶段条显式映射、验收不通过退回开发或测试、最新 PRD 草稿冻结选择。
