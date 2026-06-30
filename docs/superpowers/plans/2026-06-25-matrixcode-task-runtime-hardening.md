# MatrixCode 第八阶段本地任务运行态加固实现计划

> **面向 AI 代理的工作者：** 本计划按测试驱动推进。先写失败测试，再实现最小代码，最后补文档和复验。

**目标：** 加固第七阶段本地长任务运行态，让桌面端自动看到运行中任务的状态变化，并让服务端任务去重标记有界保留。

**架构：** 服务端只修改 `LocalTaskQueueService` 的已接收任务标记保留策略；桌面端只修改 `App` 的工作台刷新节奏。工作台聚合、任务日志接口、取消接口和安全审批边界保持不变。

**技术栈：** Java 21、Spring Boot 3.5.15、JUnit 5、Awaitility、React 19.2.7、TypeScript 6.0.3、Vitest 4.1.9、本地 Maven `/Users/Masons/Ai/Maven` 和仓库 `/Users/Masons/Ai/Maven_Ai_Store`。

---

## 范围检查

本计划只实现运行态体验和内存边界加固。第八阶段不做真实 SSH、Docker Compose 生命周期、WebSocket/SSE 日志流、数据库持久化、任务重试、优先级、多成员权限或登录鉴权。

## 文件结构

```text
server/src/main/java/com/matrixcode/localexecution/application/
└── LocalTaskQueueService.java

server/src/test/java/com/matrixcode/localexecution/
└── LocalTaskQueueServiceTest.java

desktop/src/
├── App.tsx
└── test/App.test.tsx

docs/development/local-run.md
docs/superpowers/plans/2026-06-23-matrixcode-mvp-vertical-slice.md
docs/superpowers/plans/2026-06-25-matrixcode-task-runtime-hardening.md
docs/superpowers/specs/2026-06-25-matrixcode-task-runtime-hardening-design.md
```

---

### 任务 1：收拢阶段文档和早期残留收尾项

**文件：**

- 创建：`docs/superpowers/specs/2026-06-25-matrixcode-task-runtime-hardening-design.md`
- 创建：`docs/superpowers/plans/2026-06-25-matrixcode-task-runtime-hardening.md`
- 修改：`docs/superpowers/plans/2026-06-23-matrixcode-mvp-vertical-slice.md`

- [x] **步骤 1：新增第八阶段规格**

记录第八阶段范围、用户体验、服务端边界、桌面端轮询策略、测试策略和验收标准。

- [x] **步骤 2：新增第八阶段计划**

记录任务拆分、文件范围、验证命令和阶段收尾方式。

- [x] **步骤 3：标记早期 MVP 计划残留收尾项**

把已经由后续复验覆盖的配置文档验证和整体验证步骤标为完成，并保留 Docker CLI 缺失时的替代校验说明。

- [x] **步骤 4：提交文档起点**

```bash
git add docs/superpowers/specs/2026-06-25-matrixcode-task-runtime-hardening-design.md docs/superpowers/plans/2026-06-25-matrixcode-task-runtime-hardening.md docs/superpowers/plans/2026-06-23-matrixcode-mvp-vertical-slice.md
git commit -m "docs: 规划第八阶段任务运行态加固"
```

---

### 任务 2：为服务端已接收任务标记增加有界保留

**文件：**

- 修改：`server/src/test/java/com/matrixcode/localexecution/LocalTaskQueueServiceTest.java`
- 修改：`server/src/main/java/com/matrixcode/localexecution/application/LocalTaskQueueService.java`

- [x] **步骤 1：编写红灯测试**

新增测试：连续提交超过保留上限的已完成任务后，最旧任务编号的已接收标记会被裁剪；同一任务如果仍在 `LocalTaskStore` 历史内，仍会被拒绝重复提交。

- [x] **步骤 2：运行测试验证失败**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalTaskQueueServiceTest test
```

预期：测试失败，原因是已接收任务标记没有有界裁剪或测试可见构造器不存在。

- [x] **步骤 3：实现有界保留结构**

把 `acceptedTasks` 改为带顺序队列的有界结构，默认保留 200 个任务编号。新增测试可见构造器允许小上限加速测试。

- [x] **步骤 4：运行服务端局部测试**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalTaskQueueServiceTest test
```

---

### 任务 3：让桌面端运行中任务自动刷新

**文件：**

- 修改：`desktop/src/test/App.test.tsx`
- 修改：`desktop/src/App.tsx`

- [x] **步骤 1：编写红灯测试**

模拟首次加载包含 `RUNNING` 任务，第二次工作台返回 `SUCCESS`，用短等待验证 2 秒后自动刷新并停止继续轮询。

- [x] **步骤 2：运行测试验证失败**

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

预期：测试失败，原因是没有定时刷新。

- [x] **步骤 3：实现运行中任务轮询**

在 `App` 中检测 `activeTasks` 中的 `QUEUED` 或 `RUNNING`，存在时设置 2 秒 interval 调用 `refreshWorkbench({ keepCurrent: true })`，终态后清理 interval。

- [x] **步骤 4：运行桌面端局部测试**

```bash
cd desktop && npm test -- src/test/App.test.tsx
```

---

### 任务 4：第八阶段整体验证和文档更新

**文件：**

- 修改：`docs/development/local-run.md`
- 修改：`docs/superpowers/plans/2026-06-25-matrixcode-task-runtime-hardening.md`

- [x] **步骤 1：补充本地运行指南**

增加第八阶段运行态自动刷新验证说明，包含启动服务端、启动桌面端、提交 `sleep 3` 长任务、观察右侧卡片自动从运行中刷新为终态。

- [x] **步骤 2：运行完整验证命令**

```bash
/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test
cd desktop && npm test
cd desktop && npm run build
cd desktop && npm run tauri:build -- --help
git diff --check
```

- [x] **步骤 3：运行浏览器验证**

启动服务端和桌面端，在浏览器中批准一个短长任务，确认右侧本地执行代理会自动刷新为终态，浏览器控制台没有 error。

- [x] **步骤 4：记录验证结果并提交**

在本计划末尾记录第八阶段验证结果，所有步骤勾选完成后提交：

```bash
git add server/src/main/java/com/matrixcode/localexecution/application/LocalTaskQueueService.java server/src/test/java/com/matrixcode/localexecution/LocalTaskQueueServiceTest.java desktop/src/App.tsx desktop/src/test/App.test.tsx docs/development/local-run.md docs/superpowers/plans/2026-06-25-matrixcode-task-runtime-hardening.md
git commit -m "feat: 加固本地任务运行态刷新和去重保留"
```

---

## 第八阶段完成后检查清单

- [x] 服务端本地任务队列测试通过。
- [x] 服务端全量测试通过。
- [x] 桌面端测试和构建通过。
- [x] Tauri 命令入口通过。
- [x] 浏览器运行态验证通过。
- [x] 文档检查通过。

## 第八阶段验证记录

- 红灯验证：`LocalTaskQueueServiceTest` 新增用例先失败，失败原因是缺少已接收任务标记上限构造器；`App.test.tsx` 新增用例先失败，失败原因是运行中任务不会自动触发第二次工作台加载。
- 服务端局部测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server -Dtest=LocalTaskQueueServiceTest test` 通过。
- 桌面端局部测试：`cd desktop && npm test -- src/test/App.test.tsx` 通过，21 个用例通过。
- 服务端全量测试：`/Users/Masons/Ai/Maven/bin/mvn -Dmaven.repo.local=/Users/Masons/Ai/Maven_Ai_Store -q -pl server test` 通过，Surefire 汇总为 `Tests run: 191, Failures: 0, Errors: 0, Skipped: 0`。
- 桌面端全量测试：`cd desktop && npm test` 通过，2 个测试文件、44 个用例通过。
- 桌面端构建：`cd desktop && npm run build` 通过，TypeScript 检查和 Vite 构建均完成。
- Tauri 命令入口：`cd desktop && npm run tauri:build -- --help` 通过并输出构建帮助。
- 文档检查：占位符、英文模板标题和错误 Maven 命令检查均无命中；`git diff --check` 通过。
- 浏览器验证：在 `http://127.0.0.1:5173/` 打开桌面端，提交并批准 `sleep 30` 后，右侧“本地执行代理”先显示 `运行中 · ALLOW · sleep 30` 和“取消任务”，无需手动操作后自动刷新为 `成功 · ALLOW · sleep 30`，并展示 `任务运行完成，退出码：0`；浏览器控制台 error 为空。
