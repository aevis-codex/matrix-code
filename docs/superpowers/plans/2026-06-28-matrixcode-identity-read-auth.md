# 第 90 阶段计划：身份成员读接口权限

> **面向 AI 代理的工作者：** 本阶段按 TDD 执行：先写匿名读取和越权读取红灯用例，再实现最小权限收紧，最后补全验证与项目图谱。

**目标：** 将项目成员列表、用户所属项目和用户级审计记录读接口接入 Sa-Token 登录态与项目成员权限，避免成员与审计数据被匿名或普通成员越权读取。

**架构：** 复用 `RequestActorResolver` 和 `ProjectMemberPermissionGuard`。成员列表要求当前请求用户是项目 ACTIVE 成员；用户维度数据要求当前请求用户先是项目 ACTIVE 成员，并且只能读取本人数据，读取他人数据需要 OWNER、ADMIN 或 MAINTAINER。

**技术栈：** Spring Boot 3、Sa-Token、MockMvc、React、Vitest。

---

## 成功标准

- 缺少身份读取项目成员列表返回 401。
- 非项目成员读取项目成员列表返回 403。
- 缺少身份读取用户所属项目和用户级审计记录返回 401。
- 普通成员读取其他用户的项目和审计记录返回 403。
- 本人或项目管理角色可以读取允许范围内的用户维度数据。
- 桌面端成员和审计读取 API 透传当前操作者和本地 Bearer token，并兼容旧 serverUrl 调用。
- 后端定向测试、前端定向测试、全量回归、真实运行环境检查和敏感信息扫描通过。

## 执行清单

- [x] 添加后端红灯用例覆盖成员列表和用户维度数据 401/403/成功。
- [x] 添加前端红灯用例覆盖成员列表和用户审计读取身份头透传。
- [x] 在 `ProjectIdentityController` 中保护项目成员列表。
- [x] 在 `ProjectIdentityController` 中保护用户所属项目和用户级审计记录。
- [x] 更新桌面端 API 和配置中心调用，兼容旧 serverUrl 调用并支持 actor 身份。
- [x] 跑后端定向测试与前端定向测试。
- [x] 跑全量回归、敏感信息扫描、真实运行环境检查。
- [x] 更新 Obsidian MatrixCode 项目图谱并回溯阶段偏差。

## 验证记录

- 红灯验证：后端新增权限用例先失败，匿名或越权读取返回 200；前端成员和审计读取身份头透传用例先失败。
- 后端定向测试：`ProjectIdentityControllerTest` 通过，10 条测试，0 failures，0 errors。
- 前端定向测试：`client.test.ts` 通过，60 条测试。
- 桌面端目标回归：`client.test.ts,App.test.tsx` 通过，114 条测试。
- 桌面端全量：`npm --prefix desktop test` 通过，120 条测试。
- 桌面端构建：`npm --prefix desktop run build` 通过。
- 服务端全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server test` 通过，488 条测试，0 failures，0 errors，7 skipped。
- 真实运行检查：MySQL、Milvus、Redis、RocketMQ 连通性通过。
- 静态检查：`git diff --check`、`git diff --cached --check` 通过。
- 安全扫描：仓库与 Obsidian 图谱敏感信息扫描通过。

## 回溯结论

- 本阶段与最初“多人实时协作智能体控制台、每个用户和角色都可追踪、真实可上线”的目标一致。
- 第 85 阶段已经保护成员新增写入口；第 90 阶段继续保护成员列表和用户审计读入口，关闭配置中心成员数据裸读风险。
- 本阶段不新增 DDL，不改变 MySQL、Milvus、Redis、RocketMQ 运行口径。
- 剩余权限主线收窄到旧工作流写入口、Sa-Token Redis Session、成员禁用/角色变更能力和统一权限拦截。
