# MatrixCode 第六阶段部署健康检查与运维记录设计规格

## 背景

前五个阶段已经完成角色工作台、模型网关、本地执行代理和人工审批闭环。运维角色目前可以登记部署目标、健康检查地址、SSH 地址和回滚说明，但系统不会探测健康检查地址，也没有独立记录部署、回滚和探测结果。

第六阶段把运维目标从“配置已记录”推进到“运行状态可见”。系统需要允许运维在桌面端触发一次受控 HTTP 健康检查，记录部署说明和回滚说明的执行记录，并把最近的运维结果展示到工作台和右侧指标栏。

本阶段继续坚持安全边界：不执行 SSH，不运行远程部署脚本，不连接生产主机，不读取凭证。健康检查只发起受限 HTTP 请求；部署和回滚只记录人工说明与状态。

## 范围

第六阶段实现以下能力：

- 对已配置的部署目标触发一次 HTTP 健康检查。
- 记录健康检查结果，包括状态、HTTP 状态码、耗时、摘要和检查时间。
- 记录一次部署操作，保存操作者、说明、结果状态和关联部署目标。
- 记录一次回滚操作，保存操作者、说明、结果状态和关联部署目标。
- 工作台响应中展示每个部署目标的最近健康检查和最近运维操作。
- 桌面端运维面板提供“运行健康检查”“记录部署”“记录回滚”操作。
- 右侧“部署状态”卡片展示最近健康状态、耗时、最近操作和回滚记录。
- 服务端、桌面端和本地运行文档补齐验证路径。

第六阶段不实现以下内容：

- 真实 SSH、SCP、rsync、Kubernetes、Helm、Docker 远程执行。
- 自动部署流水线、定时健康检查、批量探测、告警通知。
- 登录鉴权、多成员审批、生产级权限模型。
- 数据库持久化和审计报表。
- 长任务队列、任务取消和流式日志。

## 用户体验

运维在“运维工作区”保存部署目标后，可以选择目标并执行三类动作：

- **运行健康检查：** 点击后服务端请求该目标的健康检查地址，并返回 `HEALTHY`、`UNHEALTHY` 或 `UNREACHABLE`。
- **记录部署：** 运维填写本次部署说明和结果，系统追加一条部署记录，不触发远程动作。
- **记录回滚：** 运维填写本次回滚说明和结果，系统追加一条回滚记录，不触发远程动作。

右侧“部署状态”卡片继续保持紧凑展示。每个部署目标显示环境名称、配置状态、远程执行边界、最近健康检查、最近部署记录和最近回滚记录。没有记录时显示简短空态。

## 领域模型

沿用现有 `DeploymentTarget`，新增最近状态字段或通过工作台摘要组合展示：

- `latestHealthCheck`：最近一次健康检查结果。
- `latestDeploymentOperation`：最近一次部署记录。
- `latestRollbackOperation`：最近一次回滚记录。

健康检查结果使用独立 record：

```java
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
}
```

健康状态枚举：

```java
public enum DeploymentHealthStatus {
    HEALTHY,
    UNHEALTHY,
    UNREACHABLE
}
```

部署和回滚记录使用统一 record：

```java
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
}
```

操作类型：

```java
public enum DeploymentOperationType {
    DEPLOYMENT,
    ROLLBACK
}
```

操作状态：

```java
public enum DeploymentOperationStatus {
    RECORDED,
    SUCCEEDED,
    FAILED
}
```

第六阶段继续使用内存存储。每个项目保留最近 20 条健康检查和最近 20 条运维操作，工作台只展示每个目标的最新记录。

## 服务端架构

部署模块新增 `DeploymentHealthService`，负责：

- 根据 `projectId` 和 `targetId` 找到部署目标。
- 校验健康检查地址。
- 使用 Java 21 标准 `HttpClient` 发起一次 GET 请求。
- 设置固定超时，避免请求阻塞工作台。
- 归档健康检查记录。

部署模块新增 `DeploymentOperationService`，负责：

- 校验部署目标存在。
- 校验操作者、说明和状态。
- 记录部署或回滚操作。
- 提供按项目、目标查询最近记录的能力。

`WorkbenchService` 继续作为聚合层，不直接发起 HTTP 请求。它只读取部署目标、健康检查记录和运维操作记录，组合成工作台响应。

## HTTP 健康检查规则

健康检查只允许 `http://` 和 `https://` URL。地址必须来自已配置的 `DeploymentTarget.healthCheckUrl`，接口不接收临时 URL，避免绕过部署目标审计。

状态判定：

- HTTP `200` 到 `399`：`HEALTHY`。
- HTTP `400` 到 `599`：`UNHEALTHY`。
- DNS 失败、连接失败、超时、协议不允许、地址非法：`UNREACHABLE`。

默认超时：

- 连接超时：2 秒。
- 请求超时：3 秒。

响应摘要：

- 成功时记录 `HTTP 200` 这类简短摘要。
- 非 2xx/3xx 时记录 `HTTP 500` 这类简短摘要。
- 异常时记录中文错误摘要，例如 `健康检查地址不可达`。

第六阶段不读取响应体，不保存请求头，不携带 Cookie、Token 或自定义 Header。

## REST API

新增接口：

```http
POST /api/projects/{projectId}/deployments/targets/{targetId}/health-checks
```

请求体：

```json
{
  "actorId": "user-ops"
}
```

响应为 `DeploymentHealthCheck`。

新增接口：

```http
POST /api/projects/{projectId}/deployments/targets/{targetId}/operations
```

请求体：

```json
{
  "actorId": "user-ops",
  "type": "DEPLOYMENT",
  "status": "SUCCEEDED",
  "note": "按发布单完成预发部署。"
}
```

响应为 `DeploymentOperationRecord`。

错误处理：

- 目标不存在：HTTP 400，`部署目标不存在`。
- 操作者为空：HTTP 400，`操作者不能为空`。
- 说明为空：HTTP 400，`操作说明不能为空`。
- 操作类型非法：HTTP 400，`运维操作类型不支持`。
- 操作状态非法：HTTP 400，`运维操作状态不支持`。

## 工作台响应

`ProjectWorkbench` 新增部署运行摘要字段：

```java
List<DeploymentRuntimeSummary> deploymentRuntimeSummaries
```

摘要字段：

```java
public record DeploymentRuntimeSummary(
        String targetId,
        DeploymentHealthCheck latestHealthCheck,
        DeploymentOperationRecord latestDeploymentOperation,
        DeploymentOperationRecord latestRollbackOperation
) {
}
```

这样可以避免修改 `DeploymentTarget` 原有语义，也避免在目标配置对象里混入运行态记录。桌面端可通过 `targetId` 把目标配置和运行态摘要匹配起来。

## 桌面端设计

API 客户端新增类型：

- `DeploymentHealthStatus`
- `DeploymentHealthCheck`
- `DeploymentOperationType`
- `DeploymentOperationStatus`
- `DeploymentOperationRecord`
- `DeploymentRuntimeSummary`

API 客户端新增函数：

- `runDeploymentHealthCheck(projectId, targetId, input, serverUrl?)`
- `recordDeploymentOperation(projectId, targetId, input, serverUrl?)`

`OpsPanel` 增加一个“部署运行记录”区域：

- 如果没有部署目标，显示空态。
- 如果已有部署目标，默认选择第一个目标。
- 提供“运行健康检查”按钮。
- 提供部署记录表单：状态选择、说明输入、提交按钮。
- 提供回滚记录表单：状态选择、说明输入、提交按钮。

`InspectorPanel` 的“部署状态”卡片展示：

- 环境名称和部署目标状态。
- 最近健康状态、HTTP 状态码、耗时。
- 最近部署记录状态和说明摘要。
- 最近回滚记录状态和说明摘要。

交互完成后，`App.tsx` 调用 `refreshWorkbench({ keepCurrent: true })` 更新工作台。

## 安全边界

本阶段不会执行部署目标中的 `sshAddress`、`deployNote` 或 `rollbackNote`。部署和回滚动作只是人工记录。

健康检查只访问部署目标已保存的 `healthCheckUrl`。接口不接受请求体传入的 URL。

服务端必须拒绝非 HTTP(S) 协议，例如 `file://`、`ssh://`、`ftp://`。

健康检查不携带任何凭证，不支持自定义 Header，不保存响应体。

## 测试策略

服务端测试覆盖：

- 配置部署目标后可以运行健康检查。
- HTTP 2xx/3xx 返回 `HEALTHY`。
- HTTP 4xx/5xx 返回 `UNHEALTHY`。
- 连接失败或协议非法返回 `UNREACHABLE`。
- 部署记录和回滚记录可以按目标查询最新记录。
- 工作台响应包含 `deploymentRuntimeSummaries`。
- REST 接口能够触发健康检查和记录运维操作。

桌面端测试覆盖：

- API 客户端调用正确路径。
- `OpsPanel` 能触发健康检查、记录部署和记录回滚。
- `InspectorPanel` 能展示最近健康检查和运维记录。
- 操作完成后刷新工作台。

运行态验证覆盖：

- 启动服务端。
- 配置部署目标，健康检查地址指向本机可控健康接口。
- 调用健康检查接口，返回 `HEALTHY`。
- 调用运维操作接口，记录部署和回滚。
- 调用工作台接口，看到部署运行摘要。

## 验收标准

- `POST /deployments/targets/{targetId}/health-checks` 能对目标健康检查地址执行一次受控探测。
- 健康检查成功、非健康和不可达三类结果都可被记录。
- `POST /deployments/targets/{targetId}/operations` 能记录部署和回滚。
- 工作台响应包含部署运行摘要，并能按 `targetId` 匹配部署目标。
- 桌面端运维面板能触发健康检查、记录部署、记录回滚。
- 右侧“部署状态”卡片能展示最新健康检查、部署记录和回滚记录。
- 所有部署/回滚记录都不触发 SSH 或远程命令。
- 服务端和桌面端测试通过，文档包含第六阶段本地验证说明。

## 后续阶段入口

第七阶段可以继续扩展以下方向：

- 长任务队列、任务取消和流式日志。
- 真实 SSH 执行和远程部署审批。
- 多成员审批、登录鉴权和角色权限。
- 数据库存储和审计报表。
- 定时健康检查、告警通知和部署趋势图。
