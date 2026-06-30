# 第 89 阶段计划：Compose 运行态动作权限

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 Compose 校验、启动、停止和日志采集动作接入 Sa-Token 登录态与项目成员权限，要求请求身份与动作操作者一致。

**架构：** 复用 `RequestActorResolver` 和 `ProjectMemberPermissionGuard`。Compose 运行态动作属于运维执行入口，必须由当前项目 ACTIVE 成员发起，并且请求身份必须等于请求体 `actorId`。

**技术栈：** Spring Boot 3、Sa-Token、MockMvc、React/Vitest。

---

## 成功标准

- 缺少身份访问 Compose 校验、启动、停止、日志采集返回 401。
- 非项目成员访问 Compose 运行态动作返回 403。
- 请求身份与 `actorId` 不一致返回 403。
- 项目 ACTIVE 成员且请求身份与 `actorId` 一致时动作成功。
- 桌面端 Compose 运行态动作 API 透传当前操作者和本地 Bearer token，并兼容旧 serverUrl 调用。
- 后端定向测试、前端定向测试、全量回归、真实运行环境检查和敏感信息扫描通过。

## 执行清单

- [x] 添加后端红灯用例覆盖 Compose 运行态动作 401/403/成功。
- [x] 添加前端红灯用例覆盖 Compose 运行态动作身份头透传。
- [x] 在 `WorkbenchController` 中保护 Compose 校验、启动、停止和日志采集。
- [x] 更新桌面端 API 和调用点，兼容旧 serverUrl 调用并支持 actor 身份。
- [x] 跑后端定向测试与前端定向测试。
- [x] 跑全量回归、敏感信息扫描、真实运行环境检查。
- [x] 更新 Obsidian MatrixCode 项目图谱并回溯阶段偏差。

## 验证记录

- 红灯验证：后端 Compose 运行态动作缺少身份、非项目成员和身份不一致用例均先失败；前端 Compose 动作身份头透传用例先失败。
- 后端定向测试：`WorkbenchControllerTest` 通过，23 条测试，0 failures，0 errors。
- 前端定向测试：`client.test.ts,App.test.tsx` 通过，114 条测试，0 failures。
- 桌面端全量：`npm --prefix desktop test` 通过，120 条测试。
- 桌面端构建：`npm --prefix desktop run build` 通过。
- 服务端全量：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -pl server test` 通过，483 条测试，0 failures，0 errors，7 skipped。
- 真实运行检查：MySQL、Milvus、Redis、RocketMQ 连通性通过。
- 静态检查：`git diff --check`、`git diff --cached --check` 通过。
- 安全扫描：仓库与 Obsidian 图谱敏感信息扫描通过。

## 回溯结论

- 本阶段与最初“多人实时协作智能体控制台、真实可上线、Sa-Token 权限控制”的目标一致。
- Compose 校验、启动、停止和日志采集已经不再匿名开放，运行态动作要求当前项目 ACTIVE 成员且请求身份与 `actorId` 一致。
- 剩余权限主线收窄到成员角色变更、产品/文档/Bug/验收等旧工作流写入口、Sa-Token Redis Session 和统一权限拦截。
