# MatrixCode 第 160 阶段：生产健康快照设计

## 目标

上线、重启或回滚后，生成一份可归档的低敏健康快照，避免只依赖终端输出判断生产是否可用。

## 设计

- 新增 `scripts/capture-production-health-snapshot.sh`。
- 输入为生产 env 文件和可选输出路径。
- 脚本要求 `MATRIXCODE_PRODUCTION_CHECK=true`，避免误用开发配置。
- 读取 `/actuator/health`，健康状态不是 `UP` 时写出报告并返回非零退出码。
- 读取 `/actuator/info`，不可访问时只标记 `WARN`，不覆盖健康主结论。
- 报告记录 Git 提交、env 文件 SHA256、Sa-Token 强制模式、Redis Session Store、RocketMQ 事件中继开关和 RocketMQ 协议门禁开关。
- 报告不记录数据库密码、API Key、Sa-Token、模型密钥、完整 prompt、模型响应、向量正文或工具输出。

## 验证

- 红灯：先新增 `capture-production-health-snapshot-test.sh`，确认当前项目缺少脚本。
- 绿灯：脚本测试覆盖未开启生产门禁、健康 `UP`、健康 `DOWN`、info 可访问、报告不泄露 env 中的密码。
- 聚合门禁：`verify-production-readiness.sh` 从 21 项扩展到 22 项，加入健康快照脚本测试。
- 发布资产：`build-production-server.sh`、`package-production-release.sh` 和部署资产验证均包含健康快照脚本。

## 回溯

- 对齐“真实可上线运行”：上线后需要有健康证据文件，而不仅是进程启动成功。
- 对齐“安全边界”：快照只记录低敏状态和指纹，不记录任何密钥或业务上下文。
- 对齐第 159 阶段风险：报告显式记录 RocketMQ 中继和协议门禁开关，避免误以为 TCP 可达就等于中继已可用。
