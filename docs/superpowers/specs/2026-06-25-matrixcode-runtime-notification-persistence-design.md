# MatrixCode 第十四阶段运行态提醒轻量持久化设计规格

## 背景

第十二阶段把运行态提醒升级为服务端内存收件箱，第十三阶段补上未读筛选和全部已读操作。当前剩余缺口是：服务端重启后，提醒历史和已读状态会丢失。对本地 MVP 来说，这会让用户刷新服务端后再次看到已经处理过的提醒。

第十四阶段先解决“服务重启恢复”这一条最直接的体验断点。当前服务端没有数据库依赖，直接引入数据库表会扩大依赖、迁移和部署范围；因此本阶段采用可配置 JSON 文件存储，作为进入正式持久化前的低风险过渡。

## 目标

- 运行态提醒记录在服务端重启后恢复。
- 单条已读和全部已读的 `readAt` 在服务端重启后保留。
- 存储路径可通过配置覆盖，测试使用临时目录隔离。
- 写入采用临时文件加原子替换，避免半写入文件污染下一次启动。
- 默认存储目录加入 Git 忽略规则，避免本地运行数据进入提交。

## 非目标

- 不新增数据库、迁移脚本、ORM 或外部存储服务。
- 不做用户级独立已读状态。
- 不做提醒删除、搜索、归档、多项目聚合或管理界面。
- 不做跨机器同步、加密存储或权限隔离。
- 不持久化本地执行任务、Compose 操作或项目事件全量历史。

## 推荐方案

采用“服务端 JSON 文件快照 + 内存收件箱”的方案：

- 新增 `RuntimeNotificationStore` 抽象，负责加载和保存提醒快照。
- 新增文件实现，默认路径为 `.matrixcode/runtime-notifications.json`。
- `RuntimeNotificationService` 启动时从存储加载快照，恢复到现有内存 Map。
- `sync`、`markRead` 和 `markAllRead` 修改收件箱后立即保存快照。
- 保存快照时按项目分组，项目内保留当前服务规则下的最近 50 条。
- 读取文件失败或文件不存在时以空收件箱启动；损坏文件不阻塞服务启动。

备选方案一是直接引入数据库表。它更接近长期形态，但当前服务端没有数据库依赖，本阶段只为一个收件箱扩展整个存储栈并不划算。备选方案二是只保存已读提醒编号集合。它实现更轻，但重启后无法保留提醒列表本身，也无法支持右侧历史回看。推荐方案在能力和复杂度之间更平衡。

## 服务端设计

### 存储模型

新增 `RuntimeNotificationSnapshot`：

```java
public record RuntimeNotificationSnapshot(
        int version,
        Map<String, List<RuntimeNotification>> projects
) {
}
```

第一个版本固定为 `1`。`projects` 的 key 是项目编号，value 是该项目最近提醒列表。提醒对象复用当前 `RuntimeNotification` 领域模型，保留 `id`、`level`、`title`、`message`、`sourceType`、`sourceId`、`occurredAt` 和 `readAt`。

### 存储接口

新增 `RuntimeNotificationStore`：

```java
public interface RuntimeNotificationStore {
    RuntimeNotificationSnapshot load();

    void save(RuntimeNotificationSnapshot snapshot);
}
```

新增两个实现：

- `FileRuntimeNotificationStore`：Spring 环境默认使用，读写 JSON 文件。
- `InMemoryRuntimeNotificationStore`：手写单元测试默认使用，不触碰磁盘。

### 配置

新增 `RuntimeNotificationStorageProperties`：

```java
@Component
@ConfigurationProperties(prefix = "matrixcode.runtime-notifications")
public class RuntimeNotificationStorageProperties {
    private Path storagePath = Path.of(".matrixcode/runtime-notifications.json");
}
```

测试可以通过 `matrixcode.runtime-notifications.storage-path` 覆盖到临时目录。`.gitignore` 增加 `.matrixcode/`。

### 读写规则

加载规则：

- 文件不存在时返回空快照。
- 文件存在且格式正确时恢复所有项目提醒。
- 文件为空、版本不支持或解析失败时返回空快照，并保留原文件。
- 加载后的提醒仍按当前排序规则返回，避免依赖文件顺序。

保存规则：

- 保存前先创建父目录。
- 先写入同目录临时文件。
- 写完后使用 `ATOMIC_MOVE` 和 `REPLACE_EXISTING` 替换目标文件。
- 文件系统不支持原子移动时，降级为普通替换。
- 保存失败抛出中文异常，调用方测试可见；正常本地运行不吞掉写入失败。

### 服务集成

`RuntimeNotificationService` 调整：

- 构造时加载快照，恢复 `notifications`。
- `sync` 合并新提醒、裁剪项目上限后保存快照。
- `markRead` 写入单条已读后保存快照。
- `markAllRead` 写入批量已读后保存快照。
- `recent` 和 `allForTesting` 行为保持不变。
- 旧的无参构造保留给轻量单元测试，内部使用内存存储。

## 桌面端设计

桌面端不新增 UI。第十三阶段已有未读数量、筛选和全部已读按钮；第十四阶段只依赖工作台响应中的 `runtimeNotifications` 继续包含恢复后的提醒和 `readAt`。

用户可感知变化：

- 服务端重启后，右侧提醒列表仍能看到之前的提醒。
- 已读提醒不会重新回到顶部提醒区域。
- 未读数量保持服务端重启前的状态。

## 数据流

1. 服务端启动，`RuntimeNotificationService` 从文件存储加载快照。
2. 用户访问工作台，服务端同步本地任务和 Compose 运行态产生的新提醒。
3. 同步逻辑保留同 ID 提醒的 `readAt`。
4. 收件箱变化后写回 JSON 文件。
5. 用户执行单条已读或全部已读，服务端更新内存并写回 JSON 文件。
6. 服务端重启后再次加载文件，桌面端读取工作台即可恢复状态。

## 错误处理

- 存储文件不存在：以空收件箱启动。
- 存储文件损坏：以空收件箱启动，不删除原文件。
- 写入失败：接口返回中文错误，避免用户误以为已读状态已经保存。
- 配置路径为空：使用默认路径。
- 存储目录无法创建：写入失败并返回中文错误。

## 测试策略

服务端单元测试：

- 文件存储缺少文件时返回空快照。
- 文件存储保存后可以重新加载提醒和 `readAt`。
- 文件内容损坏时加载为空且不抛出异常。
- `RuntimeNotificationService` 使用文件存储时，重建服务实例后能恢复提醒和已读状态。
- `sync` 在恢复快照后遇到同 ID 提醒时保留 `readAt`。
- `markAllRead` 写回后，重建服务实例仍能看到全部提醒为已读。

服务端集成测试：

- 工作台接口生成提醒后，批量已读写入存储文件。
- 使用同一存储文件创建新的提醒服务实例，工作台读取到已读提醒。

桌面端测试：

- 不新增桌面端单元测试；第十三阶段已有 UI 测试继续覆盖读取恢复后的 `runtimeNotifications`。

浏览器验证：

- 使用临时存储路径启动服务端。
- 生成一条待审批提醒和一条成功提醒，确认页面显示「未读 2」。
- 点击「全部已读」，确认未读数量变为 0。
- 停止服务端并使用同一存储路径重启。
- 刷新页面后，确认顶部无提醒，右侧仍保留两条「已读」提醒。
- 浏览器控制台无 error，服务端和 Vite 停止后端口释放。

## 验收标准

- 运行态提醒和已读状态能跨服务端重启恢复。
- 默认本地存储目录不会进入 Git 提交。
- 文件不存在和损坏文件不会阻塞服务启动。
- 写入失败能以中文错误暴露。
- 服务端全量测试、桌面端全量测试、构建和浏览器重启验证全部通过。

## 后续阶段入口

第十五阶段可评估用户级提醒视图：同一项目提醒记录共用，但按用户维护独立已读状态。也可以评估把 JSON 快照迁移到正式数据库表，作为多成员协作和审计报表的基础。
