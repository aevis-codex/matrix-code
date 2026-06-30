# Sa-Token 敏感写操作权限实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将角色智能体配置更新和项目成员新增纳入 Sa-Token 登录态与项目管理角色校验。

**架构：** 复用现有 `RequestActorResolver` 解析 Sa-Token 或过渡期 Bearer 身份令牌，新增项目成员权限守卫统一判断 OWNER、ADMIN、MAINTAINER。桌面端写操作携带当前操作者身份和登录令牌，读取接口保持兼容。

**技术栈：** Spring Boot 3、Sa-Token、MockMvc、React、Vitest。

---

## 文件职责

- 修改：`server/src/test/java/com/matrixcode/identity/ProjectIdentityControllerTest.java`，为新增项目成员接口补充未登录、非管理员、管理员成功三类行为测试。
- 修改：`server/src/test/java/com/matrixcode/roleagent/RoleAgentConfigControllerTest.java`，为角色智能体配置更新接口补充未登录、非管理员、管理员成功三类行为测试。
- 创建：`server/src/main/java/com/matrixcode/identity/application/ProjectMemberPermissionGuard.java`，集中校验项目管理角色。
- 修改：`server/src/main/java/com/matrixcode/identity/api/ProjectIdentityController.java`，新增成员前解析当前操作者并校验项目管理权限。
- 修改：`server/src/main/java/com/matrixcode/roleagent/api/RoleAgentConfigController.java`，更新角色智能体配置前解析当前操作者并校验项目管理权限。
- 修改：`desktop/src/api/client.ts`，让新增成员和更新角色智能体配置支持传入 actorUserId 并携带身份请求头。
- 修改：`desktop/src/api/client.test.ts`，验证写操作请求头包含当前操作者与 Bearer token。
- 修改：`desktop/src/App.tsx`、`desktop/src/components/RoleAgentConfigDialog.tsx`，把当前操作者传入配置中心写操作。
- 修改：Obsidian `MatrixCode` 项目图谱，记录第 85 阶段完成情况和回溯结论。

## 任务 1：后端红灯测试

- [x] **步骤 1：编写失败的 MockMvc 测试**

在 `ProjectIdentityControllerTest` 中增加：

```java
repository.ensureMember(member("demo", "owner", "OWNER"));
mockMvc.perform(post("/api/projects/demo/identity/members")
        .header(RequestActorResolver.CURRENT_USER_HEADER, "owner")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userId\":\"user-dev\",\"displayName\":\"开发\",\"roleKey\":\"DEVELOPER\"}"))
        .andExpect(status().isOk());
```

在 `RoleAgentConfigControllerTest` 中增加：

```java
repository.ensureMember(member("demo", "user-dev", "DEVELOPER"));
mockMvc.perform(put("/api/projects/demo/role-agent-configs/developer")
        .header(RequestActorResolver.CURRENT_USER_HEADER, "user-dev")
        .contentType(MediaType.APPLICATION_JSON)
        .content(validDeveloperConfigJson()))
        .andExpect(status().isForbidden());
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server -Dtest=ProjectIdentityControllerTest,RoleAgentConfigControllerTest test
```

预期：至少一个新增测试失败，因为接口还没有强制校验项目管理权限。

## 任务 2：后端实现

- [x] **步骤 3：实现最少后端权限代码**

新增 `ProjectMemberPermissionGuard`：

```java
public void assertCanManageProject(String projectId, String userId) {
    var allowed = identityService.members(projectId).stream()
            .filter(member -> userId.equals(member.userId()))
            .filter(this::activeMember)
            .map(this::normalizedRoleKey)
            .anyMatch(MANAGEMENT_ROLE_KEYS::contains);
    if (!allowed) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要项目管理权限");
    }
}
```

在两个写接口中：

```java
var currentUserId = actorResolver.resolve(httpRequest);
permissionGuard.assertCanManageProject(projectId, currentUserId);
```

- [x] **步骤 4：运行后端目标测试验证通过**

运行：

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server -Dtest=ProjectIdentityControllerTest,RoleAgentConfigControllerTest test
```

预期：目标测试全部通过。

## 任务 3：桌面端红灯测试

- [x] **步骤 5：编写失败的 Vitest 测试**

在 `desktop/src/api/client.test.ts` 中验证：

```ts
window.localStorage.setItem('matrixcode.actorToken', 'token-1');
await updateRoleAgentConfig('demo', 'developer', input, 'owner', 'http://localhost:8080');
expect(fetchMock).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({
  headers: expect.objectContaining({
    Authorization: 'Bearer token-1',
    'X-MatrixCode-User-Id': 'owner'
  })
}));
```

- [x] **步骤 6：运行前端目标测试验证失败**

运行：

```bash
npm --prefix desktop test -- src/api/client.test.ts src/test/App.test.tsx
```

预期：写操作请求头断言失败。

## 任务 4：桌面端实现

- [x] **步骤 7：实现最少桌面端调用链**

`addProjectMember` 和 `updateRoleAgentConfig` 支持 `(actorUserId, serverUrl)` 形式；`App` 与配置弹窗传递当前操作者。

- [x] **步骤 8：运行前端目标测试验证通过**

运行：

```bash
npm --prefix desktop test -- src/api/client.test.ts src/test/App.test.tsx
```

预期：目标测试全部通过。

## 任务 5：全量验证、文档、提交

- [x] **步骤 9：运行完整验证**

运行：

```bash
npm --prefix desktop test
npm --prefix desktop run build
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server test
./scripts/check-real-runtime.sh .env.local
git diff --check
```

预期：全部命令退出码为 0。

- [x] **步骤 10：更新第二大脑、回溯阶段偏差、提交并推送**

更新 Obsidian 项目首页、阶段索引、模块地图、技术栈与运行约定、验证与风险，再提交并推送到 `origin/master`。
