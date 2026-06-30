# 第 86 阶段计划：本地文件读取与 Git Diff 成员权限

## 目标

将本地执行代理中的目录列表、文件读取、Git diff 采集接入 Sa-Token 登录态与项目成员权限，避免未登录用户或非项目成员读取工作区内容。

## 成功标准

- 未携带登录身份访问 `/files/list`、`/files/read`、`/git-diff` 返回 401。
- 非当前项目成员访问上述接口返回 403。
- 当前项目 ACTIVE 成员可以读取目录、文件和 Git diff。
- 桌面端调用上述敏感读接口时可携带当前登录用户身份头和 actor token。
- 后端定向测试、前端定向测试、全量后端测试、桌面端测试与真实中间件连通检查通过。

## 执行清单

- [x] 添加后端红灯用例覆盖目录列表、文件读取、Git diff 的 401/403。
- [x] 添加前端红灯用例覆盖敏感读接口身份头透传。
- [x] 在 `ProjectMemberPermissionGuard` 中补充成员级权限校验。
- [x] 在 `LocalExecutionController` 中保护目录列表、文件读取、Git diff。
- [x] 更新桌面端 API 兼容旧 serverUrl 调用并支持 actor 身份。
- [x] 跑后端定向测试与前端定向测试。
- [x] 跑全量回归、敏感信息扫描、真实运行环境检查。
- [x] 更新 Obsidian MatrixCode 项目图谱并回溯阶段偏差。
