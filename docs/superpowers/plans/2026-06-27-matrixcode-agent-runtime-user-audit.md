# MatrixCode Agent Runtime 用户责任审计实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 Agent Runtime 增加按用户查看运行责任、模型请求和低敏事件摘要的只读审计入口。

**架构：** 新增 `AgentRuntimeUserAuditService` 聚合 Agent 运行、事件、模型请求和项目成员。新增报告/条目两个领域对象，并通过独立控制器暴露 `user-audit` 入口，避免扩大既有运行控制器构造器。

**技术栈：** Java 21、Spring Boot、JUnit 5、MockMvc、既有 Agent Runtime、模型网关和身份成员服务。

---

## 文件结构

- 创建：`server/src/main/java/com/matrixcode/agentruntime/domain/AgentRuntimeUserAuditEntry.java`，单条用户责任审计条目。
- 创建：`server/src/main/java/com/matrixcode/agentruntime/domain/AgentRuntimeUserAuditReport.java`，用户级审计报告。
- 创建：`server/src/main/java/com/matrixcode/agentruntime/application/AgentRuntimeUserAuditService.java`，聚合运行、事件、模型请求和成员责任人。
- 创建：`server/src/main/java/com/matrixcode/agentruntime/api/AgentRuntimeUserAuditController.java`，暴露只读 HTTP 入口。
- 创建：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeUserAuditServiceTest.java`，覆盖服务聚合行为。
- 创建：`server/src/test/java/com/matrixcode/agentruntime/AgentRuntimeUserAuditControllerTest.java`，覆盖 HTTP 行为。
- 修改：`server/src/test/java/com/matrixcode/persistence/application/RealRuntimeIntegrationTest.java`，真实 MySQL/模型链路覆盖用户审计报告。
- 修改：Obsidian `MatrixCode` 项目图谱，记录第 73 阶段和回溯结论。

## 任务 1：服务层用户责任审计

- [x] **步骤 1：编写失败测试**

在 `AgentRuntimeUserAuditServiceTest` 新增测试：用户是当前认领 Worker 时，报告包含该运行，责任来源为 `CLAIMED_WORKER`，事件计数和模型请求计数正确。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeUserAuditServiceTest test
```

结果：FAIL，缺少报告对象和服务类，符合红灯预期。

- [x] **步骤 3：实现最少领域对象和服务**

新增 `AgentRuntimeUserAuditEntry`、`AgentRuntimeUserAuditReport` 和 `AgentRuntimeUserAuditService`。服务只读聚合，不写状态。

- [x] **步骤 4：运行测试通过**

运行同上命令，结果 PASS。

## 任务 2：责任人回退和 HTTP 入口

- [x] **步骤 1：编写失败测试**

在服务测试新增：用户不相关时报告为空；运行没有认领人但项目成员角色匹配时，责任来源为 `ROLE_MEMBER`。

在控制器测试新增：`GET /user-audit` 返回报告结构和条目。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=AgentRuntimeUserAuditServiceTest,AgentRuntimeUserAuditControllerTest test
```

结果：FAIL，HTTP 入口尚不存在或责任人回退尚未实现，符合红灯预期。

- [x] **步骤 3：实现责任人回退和控制器**

新增 `AgentRuntimeUserAuditController`；服务按 `CLAIMED_WORKER -> ROLE_MEMBER -> RUN_ACTOR` 决定责任人和责任来源。

- [x] **步骤 4：运行测试通过**

运行同上命令，结果 PASS。

## 任务 3：真实集成和完整验证

- [x] **步骤 1：补真实集成**

在 `RealRuntimeIntegrationTest` 中获取 `AgentRuntimeUserAuditService`，对已触发 Worker 模型请求的用户查询审计报告，断言包含当前 runId、模型请求计数和责任人。

- [x] **步骤 2：运行完整验证**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
npm --prefix desktop test
npm --prefix desktop run build
./scripts/check-real-runtime.sh .env.local
set -a; source .env.local; set +a; /Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dmatrixcode.real-runtime-test=true -Dtest=RealRuntimeIntegrationTest test
git diff --check
```

结果：测试、构建、真实运行检查、真实集成和静态检查通过。

- [x] **步骤 3：安全扫描**

运行当前运行路径旧地址、旧 collection 和真实密钥扫描，预期无输出。

- [x] **步骤 4：更新第二大脑并提交**

新增 `阶段成果/73 Agent Runtime 用户责任审计.md`，更新首页、阶段索引、模块地图、验证与风险、模型网关与上下文门禁、状态持久化与数据库迁移页。

提交：`feat(agent-runtime): 增加用户责任审计`

## 实际验证记录

- 服务端目标测试：`AgentRuntimeUserAuditServiceTest`、`AgentRuntimeUserAuditControllerTest` 通过。
- 服务端关联测试：`AgentRuntimeUserAuditServiceTest,AgentRuntimeUserAuditControllerTest,RealRuntimeIntegrationTest` 通过。
- 服务端全量：`files=84 tests=409 failures=0 errors=0 skipped=7`。
- 桌面端全量：`npm --prefix desktop test`，`102 passed`。
- 桌面端构建：`npm --prefix desktop run build` 通过。
- 真实运行检查：`./scripts/check-real-runtime.sh .env.local` 通过 MySQL、Milvus、Redis 和 RocketMQ 检查。
- 真实集成：`RealRuntimeIntegrationTest tests=7 failures=0 errors=0 skipped=0`，耗时 `450.0 s`。
- 静态检查：`git diff --check` 通过。
- 运行口径扫描：旧地址和旧向量集合名无命中。
- 敏感信息扫描：本阶段 diff 未出现真实模型密钥、数据库密码或历史密钥片段。

## 阶段回溯

- 与最初需求对齐：本阶段补齐多人协作智能体控制台中的「谁负责当前运行」审计能力，服务端只返回低敏运行、事件和模型请求摘要。
- 与第 38 到 40 阶段对齐：复用用户、项目成员、成员角色和模型请求操作者字段，不新增重复身份表。
- 与第 67 到 72 阶段对齐：恢复重试、队列认领、Worker 租约、执行计划和受控模型执行已能串联到用户责任审计；没有绕过审批边界。
- 与安全边界对齐：不新增 DDL，不执行命令，不读写工作区文件，不应用 Patch，不保存或返回完整 prompt、模型响应、向量正文、工具输出和密钥。
- 下一阶段建议：第 74 阶段推进完整认证权限或运行中心用户责任审计入口，使后端审计能力进入可用 UI。

## 自检

- 规格覆盖度：用户责任审计、责任人回退、HTTP、真实集成、第二大脑和安全扫描均有任务。
- 占位符扫描：计划无“待定”“TODO”“后续实现”占位。
- 类型一致性：统一使用 `AgentRuntimeUserAuditEntry`、`AgentRuntimeUserAuditReport`、`AgentRuntimeUserAuditService` 和 `user-audit`。
